package com.marop2.verity;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Фигура демона (ТЗ sec 6, demon.appearance: RIG): высокая, ~4.8 блока,
 * тонкая чёрная фигура из ~20 BlockDisplay — ноги (бедро+голень), торс, шея,
 * голова, руки (плечо+предплечье+3 пальца), глаза white_concrete brightness 15/15.
 * Обновление каждый тик: телепорт к телу с поворотом по yaw, teleportDuration 2
 * — движение плавное. Пропорции свои, не копия мода.
 */
public final class DemonRig {

    private static final double HEIGHT = 4.8;

    private record Part(BlockDisplay display, double lx, double ly, double lz,
                        double w, double h, double d) {}

    private final VerityPlugin plugin;
    private final UUID bodyId;
    private final List<Part> parts = new ArrayList<>();
    private long tick = 0;

    public DemonRig(VerityPlugin plugin, org.bukkit.entity.LivingEntity body) {
        this.plugin = plugin;
        this.bodyId = body.getUniqueId();
        spawnParts(body.getLocation());
    }

    private void spawnParts(Location base) {
        double yOff = plugin.getConfig().getDouble("demon.rig.y-offset", 0.0);
        Material bodyMat = Material.BLACK_CONCRETE;
        Material eyeMat = Material.WHITE_CONCRETE;

        // --- ноги: голень (y 0..1.1) + бедро (y 1.1..2.2), x = ±0.18 ---
        for (double sx : new double[]{-0.18, 0.18}) {
            part(base, bodyMat, sx, 0.0 + yOff, 0.0, 0.18, 1.1, 0.18);          // голень
            part(base, bodyMat, sx, 1.1 + yOff, 0.0, 0.22, 1.1, 0.22);          // бедро
        }
        // --- торс (y 2.2..3.8) ---
        part(base, bodyMat, 0.0, 2.2 + yOff, 0.0, 0.55, 1.6, 0.30);
        // --- шея (y 3.8..4.1) ---
        part(base, bodyMat, 0.0, 3.8 + yOff, 0.0, 0.18, 0.30, 0.18);
        // --- голова (y 4.1..4.8) ---
        part(base, bodyMat, 0.0, 4.1 + yOff, 0.0, 0.70, 0.70, 0.70);
        // --- глаза: два светящихся кубика на лицевой стороне головы ---
        for (double ex : new double[]{-0.15, 0.15}) {
            part(base, eyeMat, ex, 4.45 + yOff, -0.36, 0.14, 0.14, 0.05, true);
        }
        // --- руки: плечо (y 3.55..2.35) + предплечье (y 2.35..1.15), x = ±0.38 ---
        for (double sx : new double[]{-0.38, 0.38}) {
            part(base, bodyMat, sx, 2.35 + yOff, 0.0, 0.16, 1.2, 0.16);         // плечо
            part(base, bodyMat, sx, 1.15 + yOff, 0.0, 0.14, 1.2, 0.14);         // предплечье
            // --- пальцы: 3 тонких шипа (y 1.15..0.8) ---
            for (double fx : new double[]{-0.07, 0.0, 0.07}) {
                part(base, bodyMat, sx + fx, 0.8 + yOff, 0.0, 0.05, 0.35, 0.05);
            }
        }
    }

    private void part(Location base, Material mat, double lx, double ly, double lz,
                      double w, double h, double d) {
        part(base, mat, lx, ly, lz, w, h, d, false);
    }

    private void part(Location base, Material mat, double lx, double ly, double lz,
                      double w, double h, double d, boolean glow) {
        BlockDisplay disp = (BlockDisplay) base.getWorld().spawnEntity(base.clone(), EntityType.BLOCK_DISPLAY);
        disp.setBlock(mat.createBlockData());
        disp.setPersistent(false);
        disp.setTeleportDuration(2);
        disp.setInterpolationDuration(2);
        disp.setViewRange(1.0f);
        disp.setShadowRadius(0f);
        if (glow) disp.setBrightness(new Display.Brightness(15, 15));
        // scale + центрирование по X/Z (низ параллелепипеда в точке спавна)
        disp.setTransformation(new org.bukkit.util.Transformation(
                new Vector3f((float) (-w / 2), 0f, (float) (-d / 2)),
                new Quaternionf(new AxisAngle4f(0f, 0f, 1f, 0f)),
                new Vector3f((float) w, (float) h, (float) d),
                new Quaternionf(new AxisAngle4f(0f, 0f, 1f, 0f))));
        parts.add(new Part(disp, lx, ly, lz, w, h, d));
    }

    /** Обновление каждый тик из DemonManager.tick: позиция тела + yaw. */
    public void updateTick() {
        org.bukkit.entity.Entity body = org.bukkit.Bukkit.getEntity(bodyId);
        if (body == null) return;
        tick++;
        Location base = body.getLocation();
        float yawRad = (float) Math.toRadians(base.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double yOff = plugin.getConfig().getDouble("demon.rig.y-offset", 0.0);
        // лёгкое покачивание IDLE: голова и руки чуть смещаются по синусу
        double sway = Math.sin(tick / 20.0) * 0.03;
        for (Part p : parts) {
            boolean isUpper = p.ly() >= 3.8;
            double lx = p.lx() + (isUpper ? sway : 0);
            // поворот локальных координат по yaw тела
            double wx = base.getX() + lx * cos - p.lz() * sin;
            double wz = base.getZ() + lx * sin + p.lz() * cos;
            Location loc = new Location(base.getWorld(), wx, base.getY() + p.ly() + yOff, wz);
            p.display().teleport(loc);
        }
    }

    public void remove() {
        for (Part p : parts) {
            if (p.display().isValid()) p.display().remove();
        }
        parts.clear();
    }

    public boolean isValid() {
        return !parts.isEmpty() && parts.get(0).display().isValid();
    }
}
