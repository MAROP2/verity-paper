package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /verity spawn [player] | /verity despawn [player] | /verity phase [player] [1-4]
 */
public final class VerityCommand implements CommandExecutor, TabCompleter {

    private final VerityManager manager;

    public VerityCommand(VerityManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Использование: /verity <spawn|despawn|phase> [игрок] [1-4]", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                Player target = resolveTarget(sender, args, 1);
                if (target == null) return true;
                manager.spawn(target, 10);
                sender.sendMessage(Component.text("Верити появилась рядом с " + target.getName() + ".", NamedTextColor.YELLOW));
            }
            case "despawn" -> {
                Player target = resolveTarget(sender, args, 1);
                if (target == null) return true;
                boolean had = manager.hasVerity(target);
                manager.despawn(target);
                sender.sendMessage(Component.text(had ? ("Верити отпущена от " + target.getName() + ".") : (target.getName() + " не наблюдали."), NamedTextColor.GRAY));
            }
            case "phase" -> {
                Player target = resolveTarget(sender, args, 1);
                if (target == null || args.length < 3) {
                    sender.sendMessage(Component.text("Использование: /verity phase <игрок> <1-4>", NamedTextColor.YELLOW));
                    return true;
                }
                int id;
                try { id = Integer.parseInt(args[2]); } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Фаза должна быть числом 1-4.", NamedTextColor.RED));
                    return true;
                }
                VerityPhase phase = VerityPhase.byId(id);
                if (phase == null) {
                    sender.sendMessage(Component.text("Фаза должна быть 1-4.", NamedTextColor.RED));
                    return true;
                }
                manager.setPhase(target, phase);
                sender.sendMessage(Component.text("Фаза Верити для " + target.getName() + ": " + phase.name(), NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Использование: /verity <spawn|despawn|phase> [игрок] [1-4]", NamedTextColor.YELLOW));
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int i) {
        if (args.length > i) {
            @SuppressWarnings("unchecked")
            Player p = org.bukkit.Bukkit.getPlayerExact(args[i]);
            if (p == null) {
                sender.sendMessage(Component.text("Игрок не найден: " + args[i], NamedTextColor.RED));
            }
            return p;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage(Component.text("Нужно указать игрока.", NamedTextColor.RED));
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("spawn"); out.add("despawn"); out.add("phase");
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("phase")) {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("phase")) {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("phase")) {
            out.add("1"); out.add("2"); out.add("3"); out.add("4");
        }
        String cur = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        out.removeIf(s -> !s.toLowerCase().startsWith(cur));
        return out;
    }
}
