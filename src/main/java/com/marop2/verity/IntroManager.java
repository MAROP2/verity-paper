package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;

/**
 * Интро-видео на 10с: жёлтый шар выплывает из темноты с ухмылкой, титр "Verity JE".
 * Серверный приближённый вариант: Darkness + Title + частицы.
 */
public final class IntroManager {
    private final VerityPlugin plugin;

    public IntroManager(VerityPlugin plugin) {
        this.plugin = plugin;
    }

    public void play(Player player) {
        // Тьма на 10с
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 0, false, false, false));
        Location loc = player.getLocation().add(player.getLocation().getDirection().multiply(5));
        ItemDisplay ball = (ItemDisplay) player.getWorld().spawnEntity(loc, EntityType.ITEM_DISPLAY);
        ball.setItemStack(new ItemStack(Material.YELLOW_WOOL));
        ball.setBillboard(Display.Billboard.FIXED);
        ball.setPersistent(false);
        ball.setViewRange(1f);
        // Выплывает из темноты: 10с приближается
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.5f, 0.8f);
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!ball.isValid() || !player.isOnline()) { if (ball.isValid()) ball.remove(); task.cancel(); return; }
            Location cur = ball.getLocation();
            Location target = player.getLocation().clone().add(0, 1.5, 0);
            Location next = cur.clone().add(target.toVector().subtract(cur.toVector()).normalize().multiply(0.3));
            next.setY(cur.getY() + 0.05);
            ball.teleport(next);
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, next.clone().add(0, 0.4, 0), 3, 0.2, 0.2, 0.2, 0.05);
            if (cur.distance(target) < 1.5) {
                task.cancel();
                ball.remove();
                // Титр
                Title t = Title.title(
                        Component.text("Verity JE", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true),
                        Component.text("He is waiting for you.", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500)));
                player.showTitle(t);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1f, 0.6f);
            }
        }, 10L, 10L);
    }
}
