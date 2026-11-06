package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Вертикальная полоса кармы: скорборд "VERITY" с рожицей и числом 0-20.
 * Paper не умеет полосы по бокам — скорборд справа = оригиналу.
 */
public final class KarmaBoard {
    private final VerityPlugin plugin;
    private final VerityManager manager;

    public KarmaBoard(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::render, 20L, 20L);
    }

    private void render() {
        if (!plugin.getConfig().getBoolean("showKarma", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!manager.hasVerity(p)) continue;
            int karma = manager.karmaOf(p);
            String face = karma <= 5 ? "\u25D4_\u25D5" : karma <= 13 ? "\u25D5\u203F\u25D5" : "\u25D5\u203F\u25D5";
            Scoreboard sb = p.getScoreboard();
            Objective obj = sb.getObjective("verity_karma");
            if (obj == null) {
                obj = sb.registerNewObjective("verity_karma", Criteria.DUMMY, Component.text("VERITY", NamedTextColor.YELLOW));
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);
                obj.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.noStyle());
            } else {
                obj.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.noStyle());
            }
            obj.getScore(face).setScore(karma);
        }
    }
}
