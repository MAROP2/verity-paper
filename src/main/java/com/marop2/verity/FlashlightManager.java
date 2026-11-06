package com.marop2.verity;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Фонарик без изменения мира (ТЗ sec 7): луч от глаз до 25 блоков,
 * точки через каждые 2.5 блока, в каждую точку с воздухом/водой шлётся
 * LIGHT[level=15] через sendBlockChange, мир на сервере не меняется.
 * Работает, пока фонарик в руке, чистится при выключении/смене слота/
 * смене мира/выходе/onDisable.
 */
public final class FlashlightManager implements Listener {
    private final VerityPlugin plugin;
    /** Точки, где мы уже поставили поддельный LIGHT — для возврата настоящим блокам. */
    private final Map<UUID, Set<Location>> sent = new HashMap<>();
    /** Состояние: фонарик включён локально для игрока. */
    private final Map<UUID, Boolean> onState = new HashMap<>();

    public FlashlightManager(VerityPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public boolean isOn(Player p) {
        return onState.getOrDefault(p.getUniqueId(), false);
    }

    public void toggle(Player p) {
        boolean now = !isOn(p);
        onState.put(p.getUniqueId(), now);
        if (!now) clear(p);
    }

    /** Соз дать фонарик в инвентарь, если его нет. */
    public void giveFlashlight(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isFlashlight(it)) return;
        }
        ItemStack it = new ItemStack(Material.STICK);
        var meta = it.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Фонарик"));
        it.setItemMeta(meta);
        p.getInventory().addItem(it);
    }

    public boolean isFlashlight(ItemStack it) {
        return it != null && it.getType() == Material.STICK
                && it.hasItemMeta() && it.getItemMeta().hasDisplayName();
    }

    public void clear(Player p) {
        Set<Location> locs = sent.remove(p.getUniqueId());
        if (locs != null) {
            for (Location loc : locs) {
                if (loc.getWorld() == null) continue;
                Block b = loc.getWorld().getBlockAt(loc);
                p.sendBlockChange(loc, b.getBlockData());
            }
        }
        onState.put(p.getUniqueId(), false);
    }

    public void clearAll() {
        for (UUID id : new HashMap<>(sent).keySet()) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && p.isOnline()) clear(p);
        }
        sent.clear();
        onState.clear();
    }

    private void tick() {
        int range = plugin.getConfig().getInt("flashlight.distance", 25);
        int level = Math.min(15, Math.max(0, plugin.getConfig().getInt("flashlight.light-level", 15)));
        double step = plugin.getConfig().getDouble("flashlight.step", 2.5);
        double othersRadius = plugin.getConfig().getDouble("flashlight.others-see-radius", 48);

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean holding = p.getInventory().getItemInMainHand().getType() == Material.STICK
                    || p.getInventory().getItemInOffHand().getType() == Material.STICK;
            boolean on = isOn(p) && holding;
            if (!on) {
                if (sent.containsKey(p.getUniqueId())) clear(p);
                continue;
            }
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, range);
            double hitDist = (hit != null && hit.getHitPosition() != null)
                    ? eye.toVector().distance(hit.getHitPosition()) : range;
            BlockData lightData = makeLight(level);
            Set<Location> current = new HashSet<>();
            for (double d = 2.0; d < hitDist - 1.0; d += step) {
                Location spot = eye.clone().add(dir.clone().multiply(d));
                Block b = spot.getBlock();
                Material t = b.getType();
                if (t.isAir() || t == Material.WATER) {
                    current.add(b.getLocation());
                }
            }
            if (!current.isEmpty()) {
                // отправить владельцу и тем, кто в пределах 48 блоков
                Set<Player> viewers = new HashSet<>();
                viewers.add(p);
                for (Player near : eye.getWorld().getNearbyEntities(eye, othersRadius, othersRadius, othersRadius).stream()
                        .filter(e -> e instanceof Player).map(e -> (Player) e).toList()) {
                    viewers.add(near);
                }
                for (Location loc : current) {
                    for (Player v : viewers) {
                        if (v.getWorld().equals(loc.getWorld())) v.sendBlockChange(loc, lightData);
                    }
                }
            }
            // вернуть настоящие блоки в точках, выпавших из луча
            Set<Location> prev = sent.getOrDefault(p.getUniqueId(), Set.of());
            for (Location old : prev) {
                if (!current.contains(old)) {
                    Block b = old.getWorld().getBlockAt(old);
                    p.sendBlockChange(old, b.getBlockData());
                }
            }
            sent.put(p.getUniqueId(), current);
        }
    }

    private static BlockData makeLight(int level) {
        BlockData data = org.bukkit.Bukkit.createBlockData(Material.LIGHT);
        if (data instanceof org.bukkit.block.data.type.Light light) light.setLevel(level);
        return data;
    }
}
