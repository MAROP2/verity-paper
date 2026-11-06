package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Коробка: ящик парит в воздухе в пределах 15 блоков.
 * Из неё каждые ~3с слышен голос. По ПКМ открывается, через 2с выпадает шар,
 * три раза отскакивает и здоровается. Карма = 10.
 */
public final class BoxManager {
    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final Map<UUID, BoxState> boxes = new HashMap<>();

    private static final class BoxState {
        final UUID targetId;
        final ItemDisplay box;
        final TextDisplay label;
        long lastVoice = 0;
        boolean opened = false;
        int bounces = 0;
        long nextBounce = 0;

        BoxState(UUID targetId, ItemDisplay box, TextDisplay label) {
            this.targetId = targetId;
            this.box = box;
            this.label = label;
        }
    }

    public BoxManager(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Создать коробку для игрока (первый заход). */
    public void spawnBox(Player player) {
        if (manager.hasVerity(player) || hasBox(player)) return;
        World world = player.getWorld();
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double dist = 5 + ThreadLocalRandom.current().nextDouble(plugin.getConfig().getDouble("box.distance", 15) - 5);
        int x = player.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
        int z = player.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
        Location loc = world.getHighestBlockAt(x, z).getLocation().add(0.5, 1.5, 0.5);

        ItemDisplay box = (ItemDisplay) world.spawnEntity(loc, EntityType.ITEM_DISPLAY);
        box.setItemStack(new ItemStack(Material.CHEST));
        box.setBillboard(Display.Billboard.FIXED);
        box.setPersistent(false);
        box.setViewRange(1.0f);
        box.setShadowRadius(0f);
        box.setShadowStrength(0f);

        TextDisplay label = (TextDisplay) world.spawnEntity(loc.clone().add(0, 0.8, 0), EntityType.TEXT_DISPLAY);
        label.text(Component.text("?", NamedTextColor.YELLOW));
        label.setBillboard(Display.Billboard.CENTER);
        label.setSeeThrough(true);
        label.setPersistent(false);

        boxes.put(player.getUniqueId(), new BoxState(player.getUniqueId(), box, label));
        player.sendMessage(Component.text("...ты слышишь шепот откуда-то справа.", NamedTextColor.GRAY));
    }

    public boolean hasBox(Player p) {
        BoxState s = boxes.get(p.getUniqueId());
        return s != null && s.box.isValid();
    }

    private void tick() {
        for (BoxState s : new ArrayList<>(boxes.values())) {
            Player p = Bukkit.getPlayer(s.targetId);
            if (p == null || !p.isOnline() || !s.box.isValid()) {
                removeBox(s);
                continue;
            }
            Location bl = s.box.getLocation();
            // Парение
            double bob = Math.sin(System.currentTimeMillis() / 500.0) * 0.15;
            bl.setY(p.getLocation().getY() + 1.5 + bob);
            s.box.teleport(bl);
            s.label.teleport(bl.clone().add(0, 0.8, 0));
            s.box.setRotation(s.box.getYaw() + 1f, 0f);

            if (!s.opened) {
                // Голос каждые ~3с
                if (System.currentTimeMillis() - s.lastVoice > plugin.getConfig().getInt("box.voice-interval-seconds", 3) * 1000L) {
                    s.lastVoice = System.currentTimeMillis();
                    p.playSound(bl, Sound.ENTITY_VILLAGER_AMBIENT, (float) plugin.getConfig().getDouble("sounds.box-voice-volume", 0.8), (float) plugin.getConfig().getDouble("sounds.box-voice-pitch", 0.7));
                    // Редкий шепот в чат
                    if (ThreadLocalRandom.current().nextDouble() < 0.15) {
                        p.sendMessage(Component.text("...ты меня слышишь?", NamedTextColor.GRAY));
                    }
                }
            } else {
                // Отскоки шара 3 раза
                if (s.bounces < plugin.getConfig().getInt("box.bounce-count", 3)) {
                    if (System.currentTimeMillis() >= s.nextBounce) {
                        s.bounces++;
                        bl.getWorld().playSound(bl, Sound.BLOCK_SLIME_BLOCK_STEP, 1f, 1f + s.bounces * 0.2f);
                        bl.getWorld().spawnParticle(Particle.SQUID_INK, bl, 5, 0.2, 0.2, 0.2, 0.05);
                        double vy = 0.4 - s.bounces * 0.1;
                        s.box.setVelocity(new org.bukkit.util.Vector(0, vy, 0));
                        s.nextBounce = System.currentTimeMillis() + 600;
                    }
                }
            }
        }
    }

    /** ПКМ по коробке → открыть: через 2с выпадает шар. */
    public boolean tryOpen(Player player) {
        BoxState s = boxes.get(player.getUniqueId());
        if (s == null || s.opened || !s.box.isValid()) return false;
        if (player.getLocation().distance(s.box.getLocation()) > 6) return false;
        s.opened = true;
        s.box.setItemStack(new ItemStack(Material.AIR));
        player.playSound(s.box.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CRIT, s.box.getLocation(), 10, 0.3, 0.3, 0.3, 0.1);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Через 2с выпадает шар
            manager.spawn(player, 3);
            manager.setKarma(player, plugin.getConfig().getInt("box.karma-on-open", 10));
            removeBox(s);
        }, plugin.getConfig().getInt("box.open-delay-seconds", 2) * 20L);
        return true;
    }

    private void removeBox(BoxState s) {
        if (s.box.isValid()) s.box.remove();
        if (s.label.isValid()) s.label.remove();
        boxes.remove(s.targetId);
    }

    public void despawnAll() {
        for (BoxState s : new ArrayList<>(boxes.values())) {
            if (s.box.isValid()) s.box.remove();
            if (s.label.isValid()) s.label.remove();
        }
        boxes.clear();
    }
}
