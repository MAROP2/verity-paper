package com.marop2.verity;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Карма 0-20 — BossBar только владельцу (ТЗ sec 3: цвет 0-6 красный, 7-13 жёлтый, 14-20 зелёный).
 * Скорборд убран: конфликт с другими плагинами и не соответствует ТЗ.
 */
public final class KarmaBoard {
    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public KarmaBoard(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::render, 20L, 20L);
    }

    private void render() {
        if (!plugin.getConfig().getBoolean("showKarma", true) && !plugin.getConfig().getBoolean("karma.show-bossbar", true)) {
            bars.values().forEach(b -> {}); // noop
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!manager.hasVerity(p)) {
                BossBar b = bars.remove(p.getUniqueId());
                if (b != null) p.hideBossBar(b);
                continue;
            }
            int karma = manager.karmaOf(p);
            NamedTextColor col = karma <= 6 ? NamedTextColor.DARK_RED : karma <= 13 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
            BossBar bar = bars.computeIfAbsent(p.getUniqueId(), id -> {
                BossBar nb = BossBar.bossBar(Component.text("", col), 0.5f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                p.showBossBar(nb);
                return nb;
            });
            bar.name(Component.text(manager.name(), col));
            bar.color(karma <= 6 ? BossBar.Color.RED : karma <= 13 ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
            bar.progress(karma / 20f);
        }
        // убрать бары ушедших
        for (UUID id : bars.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) bars.remove(id);
        }
    }
}
