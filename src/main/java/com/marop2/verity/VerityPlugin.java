package com.marop2.verity;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

public final class VerityPlugin extends JavaPlugin implements Listener {

    private VerityManager manager;
    private DialogueManager dialogue;
    private DemonManager demonManager;
    private BoxManager boxManager;
    private AiChatManager aiChatManager;
    private IntroManager introManager;
    private KarmaBoard karmaBoard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.dialogue = new DialogueManager(this);
        this.manager = new VerityManager(this, dialogue);
        this.demonManager = new DemonManager(this, manager);
        this.boxManager = new BoxManager(this, manager);
        this.aiChatManager = new AiChatManager(this, manager);
        this.introManager = new IntroManager(this);
        this.karmaBoard = new KarmaBoard(this, manager);

        getServer().getPluginManager().registerEvents(this, this);

        VerityCommand cmd = new VerityCommand(manager, demonManager, boxManager, aiChatManager, introManager);
        register("verity", cmd);
        register("recoververity", new RecoverCommand());
        register("clear_history", new ClearHistoryCommand());
        register("changekarma", new ChangeKarmaCommand());

        // Тик мира: дней-цикл, окно, трансформация, фонарик (блок света)
        getServer().getScheduler().runTaskTimer(this, this::dayTick, 20L, 20L);

        getLogger().info("Verity is watching. Always.");
    }

    private void register(String name, CommandExecutor exec) {
        Command paperCmd = new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return exec.onCommand(sender, this, label, args);
            }
        };
        paperCmd.setDescription(name);
        getServer().getCommandMap().register("verity", paperCmd);
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.despawnAll();
        if (demonManager != null) demonManager.despawnAll();
        if (boxManager != null) boxManager.despawnAll();
    }

    public VerityManager getManager() { return manager; }
    public DialogueManager getDialogue() { return dialogue; }
    public DemonManager getDemonManager() { return demonManager; }
    public BoxManager getBoxManager() { return boxManager; }
    public AiChatManager getAiChatManager() { return aiChatManager; }
    public IntroManager getIntroManager() { return introManager; }

    /* ---------------- join / quit / chunk ---------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean first = !player.hasPlayedBefore();
        if (first) {
            player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.TORCH, 1));
            if (getConfig().getBoolean("playVideo", true)) introManager.play(player);
            boxManager.spawnBox(player);
            manager.speakTo(player, "Ты... пришёл? Я ждала. Я всегда жду.");
        } else if (getConfig().getBoolean("auto-spawn.enabled", true)) {
            double chance = getConfig().getDouble("auto-spawn.chance", 0.3);
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                int min = getConfig().getInt("auto-spawn.min-delay-seconds", 30);
                int max = getConfig().getInt("auto-spawn.max-delay-seconds", 120);
                int delay = ThreadLocalRandom.current().nextInt(min, max + 1) * 20;
                int maxDist = getConfig().getInt("auto-spawn.max-distance", 10);
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (!player.isOnline() || manager.hasVerity(player)) return;
                    manager.spawn(player, maxDist);
                }, delay);
            }
        }
        // День 2: шара здоровается "Something is coming in 3 days"
        long fullTime = player.getWorld().getFullTime();
        long day = (fullTime / 24000L) + 1;
        if (day == 2 && manager.hasVerity(player)) {
            manager.speakTo(player, getConfig().getString("replies.day2.alt", "Something is coming in 3 days"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.getPlayer().resetPlayerTime();
        event.getPlayer().resetPlayerWeather();
        manager.despawn(event.getPlayer());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.handleChunkUnload(event.getChunk());
    }

    /* ---------------- chat / AI ---------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        if (!manager.hasVerity(p)) return;
        String msg = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        // NOFACE: одним словом
        if (manager.phaseOf(p) == VerityPhase.NOFACE) {
            String[] one = {"Die", "Run", "No"};
            manager.speakTo(p, one[ThreadLocalRandom.current().nextInt(one.length)]);
            // Успокоение: подойди на 10 блоков, посмотри, напиши "i came back for you"
            String low = msg.trim().toLowerCase();
            if (low.equals("i came back for you")) {
                forgive(p, "Ты вернулся. Я... я знал, что ты вернёшься.");
            } else if (low.equals("i'm sorry") || low.equals("im sorry")) {
                manager.onSorry(p);
                if (manager.sorryOk(p)) forgive(p, getConfig().getString("replies.forgive", "I forgive you. This time."));
            }
        }
        if (msg.trim().toLowerCase().equals("i came back for you")) {
            forgive(p, "Ты вернулся. Я знал.");
        }
        // Day2 Q/A
        String low = msg.trim().toLowerCase();
        if (low.equals("что?") || low.equals("what?") || low.equals("что") || low.equals("what")) {
            manager.speakTo(p, getConfig().getString("replies.day2-what", "Something"));
        } else if (low.contains("как предотвратить") || low.contains("как предотврат") || low.contains("prevent")) {
            manager.speakTo(p, getConfig().getString("replies.day2-prevent", "You could have."));
        }
        if (low.equals("i'm sorry") || low.equals("im sorry")) {
            manager.onSorry(p);
            if (manager.sorryOk(p)) forgive(p, getConfig().getString("replies.forgive", "I forgive you. This time."));
        }
        aiChatManager.onChat(p, msg);
    }

    private void forgive(Player p, String thanks) {
        manager.setPhase(p, VerityPhase.CALM);
        manager.speakTo(p, thanks);
    }

    /* ---------------- interact: box & ball reactions ---------------- */

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!event.hasBlock()) return;
        // Box: ПКМ по коробке
        if (boxManager.hasBox(p) && event.getClickedBlock() != null && p.getLocation().distance(event.getInteractionPoint()) < 6) {
            if (boxManager.tryOpen(p)) return;
        }
        // Ball reactions
        if (!manager.hasVerity(p)) return;
        // Бросок (Shift+ПКМ): кричит AAAAAAAHHH
        if (p.isSneaking() && a == Action.RIGHT_CLICK_AIR) {
            manager.speakTo(p, getConfig().getString("replies.thrown", "AAAAAAAAHHH"));
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (boxManager.hasBox(p)) {
            // ПКМ по коробке как по сущности — тоже открыть
            if (boxManager.tryOpen(p)) return;
        }
        // Запереть в сундуке: вылезает
        if (manager.hasVerity(p) && event.getRightClicked() != null) {
            Entity e = event.getRightClicked();
            if (e.getType() == org.bukkit.entity.EntityType.ITEM_DISPLAY) {
                manager.speakTo(p, getConfig().getString("replies.trapped", "You can't trap me lil bro."));
            }
        }
    }

    /* ---------------- damage / death / combust ---------------- */

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!manager.hasVerity(p)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        // Огонь: IT BURNS, -2 карма
        if (cause == EntityDamageEvent.DamageCause.FIRE || cause == EntityDamageEvent.DamageCause.FIRE_TICK || cause == EntityDamageEvent.DamageCause.LAVA) {
            manager.speakTo(p, getConfig().getString("replies.fire", "IT BURNS"));
            manager.addKarma(p, getConfig().getInt("karma.fire-delta", -2));
        }
        // Взрыв: "Ayo chat why u let me explode"
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            manager.speakTo(p, getConfig().getString("replies.explosion", "Ayo chat why u let me explode"));
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Player p && manager.hasVerity(p)) {
            manager.speakTo(p, getConfig().getString("replies.fire", "IT BURNS"));
            manager.addKarma(p, getConfig().getInt("karma.fire-delta", -2));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        demonManager.onPlayerDeath(event);
    }

    @EventHandler
    public void onDemonDeath(EntityDeathEvent event) {
        demonManager.onDemonDeath(event);
    }

    @EventHandler
    public void onDemonDamage(EntityDamageByEntityEvent event) {
        // Демон бессмертен? Нет — его можно убить (хорошая концовка). Шар бессмертен:
        if (event.getEntity() instanceof Player p && manager.hasVerity(p)) {
            Entity damager = event.getDamager();
            if (damager instanceof Player attacker) {
                // Игрок бьёт шар: шар кричит, но не умирает
                manager.speakTo(p, "Не делай так. Я не умираю.");
                event.setCancelled(true);
            }
        }
        // Демон: от игрока — можно убить, от мобов — нет
        if (demonManager.isDemon(event.getDamager())) {
            return; // демон бьёт — как обычно
        }
    }

    @EventHandler
    public void onDemonDamage2(EntityDamageByEntityEvent event) {
        // Игрок в ярости бьёт демона — оставить урон (можно убить)
        // Мобы не могут бить демона
        if (demonManager.isDemon(event.getEntity()) && event.getDamager() instanceof Monster) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBed(PlayerBedEnterEvent event) {
        Player p = event.getPlayer();
        if (!manager.hasVerity(p)) return;
        if (demonManager.anyDemonNear(p.getLocation(), getConfig().getDouble("demon.sleep-danger-distance", 64))) {
            event.setUseBed(org.bukkit.event.Event.Result.DENY);
            p.sendMessage(Component.text("Он рядом. Спать нельзя.", NamedTextColor.DARK_RED));
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        // День 2: убиваем всё живое, кроме игроков — через gamerule + ручная чистка
        // Жители/животные часто умирают сразу при появлении (killVillagers)
        if (!getConfig().getBoolean("killVillagers", true)) return;
        if (event.getEntity() instanceof org.bukkit.entity.Villager
                || event.getEntity() instanceof org.bukkit.entity.Cow
                || event.getEntity() instanceof org.bukkit.entity.Pig
                || event.getEntity() instanceof org.bukkit.entity.Sheep
                || event.getEntity() instanceof org.bukkit.entity.Chicken) {
            if (ThreadLocalRandom.current().nextDouble() < 0.7) {
                event.setCancelled(true);
            }
        }
    }

    /* ---------------- day tick ---------------- */

    private void dayTick() {
        for (Player p : getServer().getOnlinePlayers()) {
            World w = p.getWorld();
            long fullTime = w.getFullTime();
            long day = (fullTime / 24000L) + 1;
            int dayCount = getConfig().getInt("dayCount", 5);

            // День 2 (ТЗ §4/§7): разовая зачистка только в радиусе 64 вокруг владельца,
            // глобальный гейтрул DO_MOB_SPAWNING НЕ трогаем (соседние EliteMobs и др.)
            if (day == 2 && !p.getScoreboardTags().contains("verity_day2_done")) {
                purgeRadius(p, 64);
                p.getScoreboardTags().add("verity_day2_done");
                if (manager.hasVerity(p)) {
                    manager.speakTo(p, getConfig().getString("replies.day2.alt", "Something is coming in 3 days"));
                }
            }

            // День 4: шар враждебный, лица evil
            if (day == 4 && manager.hasVerity(p) && manager.phaseOf(p) != VerityPhase.HOSTILE) {
                manager.setPhase(p, VerityPhase.HOSTILE);
                manager.face(p, FaceKind.EVIL);
            }

            // Трансформация: день 5 или transform_next_day (ИИ)
            boolean needTransform = day >= dayCount;
            boolean aiTransform = p.getScoreboardTags().contains("verity_transform_tomorrow")
                    && day >= Long.parseLong(p.getScoreboardTags().stream().filter(s -> s.startsWith("verity_transform_day_")).findFirst().map(s -> s.replace("verity_transform_day_", "")).orElse("0"));
            if ((needTransform || aiTransform) && manager.hasVerity(p)
                    && manager.phaseOf(p) != VerityPhase.NOFACE
                    && !p.getScoreboardTags().contains("verity_transformed")) {
                doTransform(p);
            }

            // 5 минут NOFACE → evil
            if (manager.hasVerity(p) && manager.phaseOf(p) == VerityPhase.NOFACE) {
                // tickInstance сам тикает; здесь — контроль 5 мин через PDC? упрощённо: если демон уже есть — уже трансформирован
            }

            // Окно с 5-го дня (DemonManager.windowScareTick)
            if (day >= dayCount) {
                demonManager.windowScareTick(p);
            }

            // Фонарик: невидимый свет по лучу 25 блоков (приближённо через частицы)
            if (!p.getInventory().getItemInMainHand().getType().isAir()) {
                var item = p.getInventory().getItemInMainHand();
                if (item.getType() == Material.TORCH || item.getType() == Material.SOUL_TORCH || item.getType() == Material.LANTERN || item.getType() == Material.SOUL_LANTERN) {
                    org.bukkit.Location eye = p.getEyeLocation();
                    org.bukkit.util.Vector dir = eye.getDirection().normalize();
                    int dist = getConfig().getInt("flashlight.distance", 25);
                    for (int i = 1; i <= dist; i++) {
                        org.bukkit.Location spot = eye.clone().add(dir.clone().multiply(i));
                        w.spawnParticle(Particle.ELECTRIC_SPARK, spot, 1, 0.3, 0.3, 0.3, 0.0);
                    }
                }
            }

            // True Darkness приближённо: ночью — Darkness
            if (getConfig().getBoolean("trueDarkness", true) && !w.isDayTime()) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 100, 0, false, false, false));
            }

            // Кислотная трава/листва — нельзя без datapack, помечаем
        }
    }


    /** Зачистка мобов только вокруг владельца (ТЗ §7). Исключения: питомцы, именованные, на поводке, с опытом торговли, рядом с чужими игроками. */
    private void purgeRadius(Player owner, double radius) {
        for (Entity e : owner.getWorld().getNearbyEntities(owner.getLocation(), radius, radius, radius)) {
            if (e instanceof Player) continue;
            if (demonManager.isDemon(e)) continue;
            if (e instanceof Monster) continue; // режим PASSIVE по умолчанию: животных/жителей
            if (e.getCustomName() != null) continue;
            if (e instanceof org.bukkit.entity.Tameable ta && ta.isTamed()) continue;
            if (e.getVehicle() != null || !e.getPassengers().isEmpty()) continue;
            if (e instanceof org.bukkit.entity.Villager v && v.getVillagerLevel() > 1) continue;

            // не трогать мобов рядом с чужими игроками (чужие фермы)
            boolean nearOther = false;
            for (Player other : getServer().getOnlinePlayers()) {
                if (other.equals(owner)) continue;
                if (other.getWorld().equals(e.getWorld()) && other.getLocation().distance(e.getLocation()) < 32) { nearOther = true; break; }
            }
            if (nearOther) continue;
            org.bukkit.Location loc = e.getLocation().clone();
            e.remove();
            e.getWorld().spawnParticle(Particle.SMOKE, loc.add(0, 0.5, 0), 10, 0.3, 0.5, 0.3, 0.02);
        }
    }

    private void doTransform(Player p) {
        World w = p.getWorld();
        manager.speakTo(p, "Verity's face goes blank...");
        w.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, (float) getConfig().getDouble("sounds.wither-spawn-pitch", 0.5));
        w.spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 30, 1, 1, 1, 0.1);
        p.setPlayerTime(18000, false);
        if (getConfig().getBoolean("requireVerity", true) && !manager.hasVerity(p)) return;
        // Демон в 16-31 блоках по X/Z
        int dx = ThreadLocalRandom.current().nextInt(16, 32) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
        int dz = ThreadLocalRandom.current().nextInt(16, 32) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
        org.bukkit.Location demonLoc = p.getLocation().clone().add(dx, 0, dz);
        demonLoc.setY(w.getHighestBlockAt(demonLoc.getBlockX(), demonLoc.getBlockZ()).getY() + 1);
        demonManager.spawnDemon(p, demonLoc, 1);
        manager.setPhase(p, VerityPhase.NOFACE);
        p.getScoreboardTags().add("verity_transformed");
    }

    private void transformNextDay(Player p) {
        p.getScoreboardTags().add("verity_transform_tomorrow");
        long tomorrow = (p.getWorld().getFullTime() / 24000L) + 2;
        p.getScoreboardTags().add("verity_transform_day_" + tomorrow);
        aiChatManager.setMemory(p.getName() + ".transform_tomorrow", String.valueOf(tomorrow));
    }

    /* ---------------- commands ---------------- */

    private final class RecoverCommand implements CommandExecutor {
        @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player p) {
                long now = System.currentTimeMillis();
                long last = p.getPersistentDataContainer().getOrDefault(new org.bukkit.NamespacedKey(VerityPlugin.this, "recover_cooldown"), org.bukkit.persistence.PersistentDataType.LONG, 0L);
                if (now - last < 3600_000L) {
                    sender.sendMessage(Component.text("Перезарядка: 1 час.", NamedTextColor.RED));
                    return true;
                }
                if (!manager.hasVerity(p)) manager.spawn(p, 10);
                p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(VerityPlugin.this, "recover_cooldown"), org.bukkit.persistence.PersistentDataType.LONG, now);
                manager.speakTo(p, "DO NOT DO THAT!");
                sender.sendMessage(Component.text("Verity возродилась рядом.", NamedTextColor.YELLOW));
            }
            return true;
        }
    }

    private final class ClearHistoryCommand implements CommandExecutor {
        @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player p) {
                aiChatManager.clearHistory(p);
                sender.sendMessage(Component.text("История очищена.", NamedTextColor.YELLOW));
            }
            return true;
        }
    }

    private final class ChangeKarmaCommand implements CommandExecutor {
        @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length < 1) { sender.sendMessage(Component.text("/changekarma 0-20", NamedTextColor.YELLOW)); return true; }
            int v;
            try { v = Integer.parseInt(args[0]); } catch (NumberFormatException ex) { sender.sendMessage(Component.text("Число 0-20.", NamedTextColor.RED)); return true; }
            if (v < 0 || v > 20) { sender.sendMessage(Component.text("Карма 0-20.", NamedTextColor.RED)); return true; }
            Player target = sender instanceof Player pl ? pl : getServer().getPlayerExact(args.length > 1 ? args[1] : "");
            if (target == null) { sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED)); return true; }
            manager.setKarma(target, v);
            sender.sendMessage(Component.text("Карма " + target.getName() + ": " + v, NamedTextColor.YELLOW));
            return true;
        }
    }
}
