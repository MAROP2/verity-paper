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
import org.bukkit.entity.Interaction;
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
 * Коробка (ТЗ sec 5): BlockDisplay-барел стоит на суше в 3-15 блоках от игрока,
 * парит на 0.3 блока выше земли и покачивается. Interaction 1x1 — хитбокс для
 * ПКМ владельца. Каждые 3с тихий голос (радиус 16). Открытие: звук, частицы,
 * коробка исчезает, шар вылетает с лицом HURT, карма = 10.
 */
public final class BoxManager {
    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final Map<UUID, BoxState> boxes = new HashMap<>();

    private static final class BoxState {
        final UUID targetId;
        final ItemDisplay box;
        final Interaction hitbox;
        long lastVoice = 0;
        boolean opened = false;

        BoxState(UUID targetId, ItemDisplay box, Interaction hitbox) {
            this.targetId = targetId;
            this.box = box;
            this.hitbox = hitbox;
        }
    }

    public BoxManager(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    /** Создать коробку для игрока (первый заход или /verity start). */
    public void spawnBox(Player player) {
        if (manager.hasVerity(player) || hasBox(player)) return;
        World world = player.getWorld();
        // 3-15 блоков, на суше
        Location loc = null;
        for (int attempt = 0; attempt < 20 && loc == null; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double dist = 3 + ThreadLocalRandom.current().nextDouble(plugin.getConfig().getDouble("box.distance", 15) - 3);
            int x = player.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = player.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            Location cand = world.getHighestBlockAt(x, z).getLocation().add(0.5, 1.0, 0.5);
            if (!cand.getBlock().isLiquid()) loc = cand;
        }
        if (loc == null) loc = player.getLocation().clone().add(3, 1, 0);

        // парение на 0.3 выше земли
        Location displayLoc = loc.clone().add(0, 0.3, 0);
        ItemDisplay box = (ItemDisplay) world.spawnEntity(displayLoc, EntityType.ITEM_DISPLAY);
        box.setItemStack(new ItemStack(Material.BARREL));
        box.setBillboard(Display.Billboard.FIXED);
        box.setPersistent(false);
        box.setViewRange(1.0f);
        box.setShadowRadius(0f);
        box.setShadowStrength(0f);

        // хитбокс 1x1 на земле — принимает ПКМ
        Interaction hitbox = (Interaction) world.spawnEntity(loc, EntityType.INTERACTION);
        hitbox.setInteractionWidth(1.0f);
        hitbox.setInteractionHeight(1.0f);
        hitbox.setResponsive(true);
        hitbox.setPersistent(false);

        boxes.put(player.getUniqueId(), new BoxState(player.getUniqueId(), box, hitbox));
        player.sendMessage(Component.text("...ты слышишь шёпот откуда-то рядом.", NamedTextColor.GRAY));
    }

    public boolean hasBox(Player p) {
        BoxState s = boxes.get(p.getUniqueId());
        return s != null && s.box.isValid();
    }

    /** Это хитбокс-сущность коробки этого игрока? */
    public boolean isBoxEntity(Player p, Entity e) {
        BoxState s = boxes.get(p.getUniqueId());
        return s != null && s.hitbox.isValid() && s.hitbox.getUniqueId().equals(e.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (BoxState s : new ArrayList<>(boxes.values())) {
            Player p = Bukkit.getPlayer(s.targetId);
            if (p == null || !p.isOnline() || !s.box.isValid() || !s.hitbox.isValid()) {
                removeBox(s);
                continue;
            }
            if (s.opened) { removeBox(s); continue; }

            // покачивание ±0.08 за 2с на месте (не за игроком!)
            Location bl = s.hitbox.getLocation();
            double bob = Math.sin(now / 2000.0 * Math.PI * 2) * 0.08;
            Location dispLoc = bl.clone().add(0, 0.3 + bob, 0);
            s.box.teleport(dispLoc);

            // тихий голос каждые 3с, слышат все в 16 блоках
            if (now - s.lastVoice > plugin.getConfig().getInt("box.voice-interval-seconds", 3) * 1000L) {
                s.lastVoice = now;
                bl.getWorld().playSound(bl, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM,
                        (float) plugin.getConfig().getDouble("sounds.box-voice-volume", 0.6),
                        (float) plugin.getConfig().getDouble("sounds.box-voice-pitch", 0.5));
                if (p.getLocation().distance(bl) < 16 && ThreadLocalRandom.current().nextDouble() < 0.15) {
                    p.sendMessage(Component.text("...ты меня слышишь?", NamedTextColor.GRAY));
                }
            }
        }
    }

    /** ПКМ владельца по коробке → открыть: частицы, шар, карма = 10. */
    public boolean tryOpen(Player player) {
        BoxState s = boxes.get(player.getUniqueId());
        if (s == null || s.opened || !s.box.isValid()) return false;
        if (player.getLocation().distance(s.hitbox.getLocation()) > 6) return false;
        s.opened = true;
        Location bl = s.hitbox.getLocation();
        player.playSound(bl, Sound.BLOCK_BARREL_OPEN, 1f, 0.8f);
        bl.getWorld().spawnParticle(Particle.CRIT, bl.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.1);
        // через 40 тиков: облако частиц, коробка исчезает, шар вылетает
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            bl.getWorld().spawnParticle(Particle.POOF, bl.clone().add(0, 0.5, 0), 20, 0.3, 0.4, 0.3, 0.05);
            removeBox(s);
            manager.spawn(player, 3);
            manager.setKarma(player, plugin.getConfig().getInt("box.karma-on-open", 10));
            manager.speakTo(player, manager.name() + ": Привет! Я " + manager.name() + ". Я останусь с тобой. Навсегда.");
        }, 40L);
        return true;
    }

    private void removeBox(BoxState s) {
        if (s.box.isValid()) s.box.remove();
        if (s.hitbox.isValid()) s.hitbox.remove();
        boxes.remove(s.targetId);
    }

    public void despawnAll() {
        for (BoxState s : new ArrayList<>(boxes.values())) {
            if (s.box.isValid()) s.box.remove();
            if (s.hitbox.isValid()) s.hitbox.remove();
        }
        boxes.clear();
    }
}
