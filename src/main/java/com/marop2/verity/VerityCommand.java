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
import java.util.Locale;

/**
 * /verity: spawn | despawn | phase | demon | box | intro | start | status |
 * recover | flashlight | clearhistory | karma | day | transform | scare | defeat | reset | reload
 */
public final class VerityCommand implements CommandExecutor, TabCompleter {

    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final DemonManager demonManager;
    private final BoxManager boxManager;
    private final AiChatManager aiChatManager;
    private final IntroManager introManager;
    private final FlashlightManager flashlightManager;

    public VerityCommand(VerityPlugin plugin, VerityManager manager, DemonManager demonManager, BoxManager boxManager,
                         AiChatManager aiChatManager, IntroManager introManager, FlashlightManager flashlightManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.demonManager = demonManager;
        this.boxManager = boxManager;
        this.aiChatManager = aiChatManager;
        this.introManager = introManager;
        this.flashlightManager = flashlightManager;
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("verity.admin") || sender.isOp();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("/verity <start|status|spawn|despawn|phase|demon|box|intro|recover|flashlight|clearhistory|karma|day|transform|scare|defeat|reset|reload>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                boxManager.spawnBox(target);
                flashlightManager.giveFlashlight(target);
                sender.sendMessage(Component.text("Цикл начат: коробка и фонарик у " + target.getName() + ".", NamedTextColor.YELLOW));
            }
            case "status" -> {
                Player target = resolveTarget(sender, args, 1, args.length > 1);
                if (target == null) return true;
                if (args.length > 1 && !isAdmin(sender)) {
                    sender.sendMessage(Component.text("Чужой статус — только для админа.", NamedTextColor.RED));
                    return true;
                }
                VerityPhase ph = manager.phaseOf(target);
                sender.sendMessage(Component.text(
                        target.getName() + ": фаза " + (ph == null ? "нет шара" : ph.name())
                                + ", карма " + manager.karmaOf(target) + "/20"
                                + ", демон " + (demonManager.isDemonActive(target) ? "активен" : "нет")
                                + ", transformed=" + target.getScoreboardTags().contains("verity_transformed"),
                        NamedTextColor.YELLOW));
            }
            case "spawn" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                manager.spawn(target, 10);
                sender.sendMessage(Component.text("Верити появилась рядом с " + target.getName() + ".", NamedTextColor.YELLOW));
            }
            case "despawn" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                boolean had = manager.hasVerity(target);
                manager.despawn(target);
                sender.sendMessage(Component.text(had ? ("Верити отпущена от " + target.getName() + ".") : (target.getName() + " не наблюдали."), NamedTextColor.GRAY));
            }
            case "phase" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                if (args.length < 3) {
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
            case "demon" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                boolean stalk = args.length > 2 && args[2].equalsIgnoreCase("stalk");
                demonManager.spawnDemon(target, target.getLocation().add(0, 0, 20), stalk ? 1 : 0);
                sender.sendMessage(Component.text("Демон вызван рядом с " + target.getName() + ".", NamedTextColor.DARK_RED));
            }
            case "box" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                boxManager.spawnBox(target);
                sender.sendMessage(Component.text("Коробка сброшена рядом с " + target.getName() + ".", NamedTextColor.YELLOW));
            }
            case "intro" -> {
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                introManager.play(target);
                sender.sendMessage(Component.text("Интро запущено для " + target.getName() + ".", NamedTextColor.YELLOW));
            }
            case "recover" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED)); return true; }
                long now = System.currentTimeMillis();
                long last = p.getPersistentDataContainer().getOrDefault(
                        new org.bukkit.NamespacedKey(plugin, "recover_cooldown"),
                        org.bukkit.persistence.PersistentDataType.LONG, 0L);
                if (now - last < 3600_000L) {
                    sender.sendMessage(Component.text("Перезарядка recover: 1 час.", NamedTextColor.RED));
                    return true;
                }
                if (!manager.hasVerity(p)) manager.spawn(p, 5);
                p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "recover_cooldown"),
                        org.bukkit.persistence.PersistentDataType.LONG, now);
                sender.sendMessage(Component.text("Новый шар появился.", NamedTextColor.YELLOW));
            }
            case "flashlight" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED)); return true; }
                flashlightManager.giveFlashlight(p);
                sender.sendMessage(Component.text("Фонарик выдан (ПКМ — вкл/выкл).", NamedTextColor.YELLOW));
            }
            case "clearhistory" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED)); return true; }
                aiChatManager.clearHistory(p);
                sender.sendMessage(Component.text("История диалога очищена.", NamedTextColor.YELLOW));
            }
            case "karma" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                if (args.length < 3) { sender.sendMessage(Component.text("Использование: /verity karma <игрок> <0-20>", NamedTextColor.YELLOW)); return true; }
                Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Игрок не найден: " + args[1], NamedTextColor.RED)); return true; }
                int v;
                try { v = Integer.parseInt(args[2]); } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Карма — число 0-20.", NamedTextColor.RED)); return true;
                }
                manager.setKarma(target, v);
                plugin.getPlayerStore().get(target.getUniqueId()).karma = manager.karmaOf(target);
                sender.sendMessage(Component.text("Карма " + target.getName() + ": " + manager.karmaOf(target), NamedTextColor.YELLOW));
            }
            case "day" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                if (args.length < 3) { sender.sendMessage(Component.text("Использование: /verity day <игрок> <n>", NamedTextColor.YELLOW)); return true; }
                Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Игрок не найден: " + args[1], NamedTextColor.RED)); return true; }
                int n;
                try { n = Integer.parseInt(args[2]); } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("День — число.", NamedTextColor.RED)); return true;
                }
                // dayOffset через PDC: день = worldDay + offset
                long worldDay = target.getWorld().getFullTime() / 24000L;
                long offset = n - worldDay;
                target.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "verity_day_offset"),
                        org.bukkit.persistence.PersistentDataType.LONG, offset);
                plugin.getPlayerStore().get(target.getUniqueId()).dayOffset = offset;
                sender.sendMessage(Component.text("День для " + target.getName() + " установлен: " + n + " (offset " + offset + ")", NamedTextColor.YELLOW));
            }
            case "transform" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                target.getScoreboardTags().add("verity_transform_now");
                sender.sendMessage(Component.text("Превращение запущено для " + target.getName() + ".", NamedTextColor.DARK_RED));
            }
            case "scare" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                if (args.length < 3 || !args[1].equalsIgnoreCase("window")) {
                    sender.sendMessage(Component.text("Использование: /verity scare window <игрок>", NamedTextColor.YELLOW));
                    return true;
                }
                Player target = org.bukkit.Bukkit.getPlayerExact(args[2]);
                if (target == null) { sender.sendMessage(Component.text("Игрок не найден: " + args[2], NamedTextColor.RED)); return true; }
                demonManager.windowScareTick(target);
                sender.sendMessage(Component.text("Скример с окном отправлен " + target.getName() + ".", NamedTextColor.DARK_RED));
            }
            case "defeat" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                demonManager.defeatDemon(target);
                sender.sendMessage(Component.text("Демон " + target.getName() + " побеждён.", NamedTextColor.YELLOW));
            }
            case "reset" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                Player target = resolveTarget(sender, args, 1, true);
                if (target == null) return true;
                manager.despawn(target);
                demonManager.despawnFor(target);
                boxManager.despawnAll();
                for (String tag : List.of("verity_transformed", "verity_transformDone", "verity_transform_tomorrow",
                        "verity_day2_done", DemonManager.TAG_WINDOW_DONE)) {
                    target.getScoreboardTags().remove(tag);
                }
                target.getScoreboardTags().removeIf(t -> t.startsWith("verity_transform_day_"));
                target.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "verity_day_offset"));
                aiChatManager.clearHistory(target);
                target.resetPlayerTime();
                target.resetPlayerWeather();
                sender.sendMessage(Component.text("Состояние " + target.getName() + " сброшено.", NamedTextColor.YELLOW));
            }
            case "reload" -> {
                if (!isAdmin(sender)) { sender.sendMessage(Component.text("Нужны права verity.admin.", NamedTextColor.RED)); return true; }
                plugin.reloadConfig();
                sender.sendMessage(Component.text("Конфиг Verity перечитан.", NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Неизвестная подкоманда. /verity — помощь.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int i, boolean allowSelf) {
        if (args.length > i) {
            if (!isAdmin(sender)) {
                sender.sendMessage(Component.text("Чужой игрок — только для админа.", NamedTextColor.RED));
                return null;
            }
            Player p = org.bukkit.Bukkit.getPlayerExact(args[i]);
            if (p == null) sender.sendMessage(Component.text("Игрок не найден: " + args[i], NamedTextColor.RED));
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
            out.addAll(List.of("start", "status", "spawn", "despawn", "phase", "demon", "box", "intro",
                    "recover", "flashlight", "clearhistory", "karma", "day", "transform", "scare", "defeat", "reset", "reload"));
        } else if (args.length == 2 && List.of("karma", "day").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("scare")) {
            out.add("window");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("scare")) {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 2) {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("phase")) {
            out.addAll(List.of("1", "2", "3", "4"));
        }
        String cur = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }
}
