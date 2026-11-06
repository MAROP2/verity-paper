package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Демон VERITY: жёлтый гуманоид 4.8 блока на теле зомби (Paper не умеет GeoEntity).
 * Модель = крупный Zombie + жёлтый TextDisplay-смайл на голове (светится в темноте).
 * DEMON_STATE 0=наблюдает/1=охота, HUNT_PHASE 0=окно/1=издалека.
 */
public final class DemonManager {

    public static final String TAG = "verity_demon";
    public static final String TAG_WINDOW_DONE = "verity_window_scare_done";

    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final Map<UUID, DemonState> demons = new HashMap<>();
    private final Map<UUID, Long> lastJumpScare = new HashMap<>();
    private final Map<UUID, Long> stareStart = new HashMap<>();
    private final Map<UUID, Long> crunchAt = new HashMap<>();
    private final Map<UUID, Player> grabVictims = new HashMap<>();
    private final Map<UUID, Integer> grabTicks = new HashMap<>();
    private final Map<UUID, Boolean> crawlingMap = new HashMap<>();
    private final Map<UUID, DemonRig> rigs = new HashMap<>();

    public static final class DemonState {
        final UUID id;
        final UUID targetId;
        int demonState = 0; // 0 observe, 1 hunt
        int huntPhase = 1;  // 0 window, 1 far
        boolean eating = false;
        long eatUntil = 0;
        boolean crawling = false;

        DemonState(UUID id, UUID targetId) { this.id = id; this.targetId = targetId; }
    }

    public DemonManager(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    /* ---------------- spawn / despawn ---------------- */

    public Zombie spawnDemon(Player target, Location loc, int huntPhase) {
        World world = loc.getWorld();
        if (world == null) return null;
        Zombie z = (Zombie) world.spawnEntity(loc, EntityType.ZOMBIE);
        z.setPersistent(false);
        z.customName(Component.text(plugin.getConfig().getString("demon.name", "VERITY"), NamedTextColor.DARK_RED));
        z.setCustomNameVisible(true);
        z.addScoreboardTag(TAG);
        applyAttributes(z);
        z.getEquipment().clear();
        z.setCanPickupItems(false);
        z.setAdult();
        boolean rig = plugin.getConfig().getString("demon.appearance", "RIG").equalsIgnoreCase("RIG");
        if (rig) {
            // тело невидимое, поверх — фигура из BlockDisplay
            z.setInvisible(true);
            rigs.put(z.getUniqueId(), new DemonRig(plugin, z));
        } else {
            // запасной вариант VANILLA: видимый моб с масштабом
            z.customName(null);
            z.setCustomNameVisible(false);
        }
        // Жёлтая голова-смайл только в VANILLA-режиме
        if (!rig) {
            Location head = loc.clone().add(0, 2.6, 0);
            var face = (org.bukkit.entity.TextDisplay) world.spawnEntity(head, EntityType.TEXT_DISPLAY);
            face.text(Component.text("◕‿◕", net.kyori.adventure.text.format.TextColor.color(0xFFFF00)));
            face.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            face.setSeeThrough(true);
            face.setPersistent(false);
            face.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        }
        demons.put(z.getUniqueId(), stateFor(z, target.getUniqueId(), huntPhase));
        z.setTarget(target);
        return z;
    }

    private void removeRig(UUID demonId) {
        DemonRig rig = rigs.remove(demonId);
        if (rig != null) rig.remove();
    }

    private DemonState stateFor(Zombie z, UUID targetId, int huntPhase) {
        DemonState s = demons.get(z.getUniqueId());
        if (s == null) {
            s = new DemonState(z.getUniqueId(), targetId);
            demons.put(z.getUniqueId(), s);
        }
        s.huntPhase = huntPhase;
        return s;
    }

    public DemonState stateOf(LivingEntity e) {
        return demons.get(e.getUniqueId());
    }

    public boolean isDemon(Entity e) {
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    public void despawnDemon(LivingEntity e) {
        if (e != null && e.isValid()) e.remove();
        if (e != null) removeRig(e.getUniqueId());
        demons.remove(e == null ? null : e.getUniqueId());
    }

    public void despawnAll() {
        for (UUID id : new ArrayList<>(demons.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) e.remove();
        }
        for (DemonRig rig : new ArrayList<>(rigs.values())) rig.remove();
        rigs.clear();
        demons.clear();
    }

    public boolean isDemonActive(Player target) {
        if (target == null) return false;
        for (DemonState s : demons.values()) {
            if (s.targetId.equals(target.getUniqueId())) return true;
        }
        return false;
    }

    public void defeatDemon(Player target) {
        if (target == null) return;
        List<Zombie> toRemove = new ArrayList<>();
        for (Zombie z : allDemons()) {
            DemonState s = demons.get(z.getUniqueId());
            if (s != null && s.targetId.equals(target.getUniqueId())) toRemove.add(z);
        }
        for (Zombie z : toRemove) {
            try { z.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, z.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.1); } catch (Exception ignored) {}
            removeRig(z.getUniqueId());
            z.remove();
            demons.remove(z.getUniqueId());
        }
        target.resetPlayerTime();
        try { target.resetPlayerWeather(); } catch (Exception ignored) {}
        target.getScoreboardTags().remove("verity_transformed");
        target.getScoreboardTags().remove("verity_transformDone");
        manager.setKarma(target, plugin.getConfig().getInt("karma.good-ending", 20));
        // шар добрый
        try { manager.setMonstrous(target, false); } catch (Exception ignored) {}
        try { manager.setPhase(target, VerityPhase.CALM); } catch (Exception ignored) {}
        try { manager.face(target, FaceKind.HAPPY); } catch (Exception ignored) {}
        try { manager.speakTo(target, plugin.getConfig().getString("replies.good-ending", "The darkness... it's gone. Thank you.")); } catch (Exception ignored) {}
        try { target.playSound(target.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f); } catch (Exception ignored) {}
        try { target.playSound(target.getLocation(), org.bukkit.Sound.ITEM_TOTEM_USE, 1f, 1f); } catch (Exception ignored) {}
    }

    public void despawnFor(Player target) {
        if (target == null) return;
        for (Zombie z : new ArrayList<>(allDemons())) {
            DemonState s = demons.get(z.getUniqueId());
            if (s != null && s.targetId.equals(target.getUniqueId())) {
                removeRig(z.getUniqueId());
                z.remove();
                demons.remove(z.getUniqueId());
            }
        }
    }

    private void applyAttributes(Zombie z) {
        setAttr(z, "generic.max_health", plugin.getConfig().getDouble("demon.health", 400.0));
        z.setHealth(Math.min(z.getHealth(), 400.0));
        setAttr(z, "generic.movement_speed", plugin.getConfig().getDouble("demon.speed", 0.45));
        setAttr(z, "generic.follow_range", plugin.getConfig().getDouble("demon.follow-range", 512.0));
        setAttr(z, "generic.attack_damage", plugin.getConfig().getDouble("demon.damage", 19.0));
        setAttr(z, "generic.knockback_resistance", plugin.getConfig().getDouble("demon.knockback-resistance", 1.0));
        setAttr(z, "generic.step_height", plugin.getConfig().getDouble("demon.step-height", 1.5));
        setAttr(z, "generic.scale", plugin.getConfig().getDouble("demon.scale", 2.4));
        z.setFireTicks(0);
        z.setNoDamageTicks(0);
    }

    private void setAttr(LivingEntity e, String key, double v) {
        Attribute a = org.bukkit.Registry.ATTRIBUTE.get(org.bukkit.NamespacedKey.fromString(key));
        if (a != null) {
            AttributeInstance inst = e.getAttribute(a);
            if (inst != null) inst.setBaseValue(v);
        }
    }

    /* ---------------- tick ---------------- */

    private void tick() {
        // Чистка мёртвых
        demons.entrySet().removeIf(en -> {
            Entity e = Bukkit.getEntity(en.getKey());
            boolean dead = e == null || !e.isValid() || e.isDead();
            if (dead) removeRig(en.getKey());
            return dead;
        });
        // Обновление фигур RIG каждый тик
        for (DemonRig rig : rigs.values()) rig.updateTick();
        for (DemonState s : new ArrayList<>(demons.values())) {
            Entity e = Bukkit.getEntity(s.id);
            if (!(e instanceof Zombie z) || !z.isValid()) continue;
            Player target = Bukkit.getPlayer(s.targetId);
            if (target == null || !target.isOnline()) continue;
            tickDemon(s, z, target);
        }
        // Охота на жителей (demon сначала охотится на жителей)
        huntVillagersTick();
        // Ползущий режим под низким потолком
        for (Zombie z : allDemons()) {
            boolean lowCeiling = isLowCeiling(z.getLocation());
            boolean wasCrawling = crawlingMap.getOrDefault(z.getUniqueId(), false);
            if (lowCeiling && !wasCrawling) {
                setAttr(z, "generic.scale", plugin.getConfig().getDouble("demon.crawl-scale", 1.0));
            } else if (!lowCeiling && wasCrawling) {
                setAttr(z, "generic.scale", plugin.getConfig().getDouble("demon.scale", 2.4));
            }
            crawlingMap.put(z.getUniqueId(), lowCeiling);
            // Ходит по воде — зомби и так ходит; добавим всплеск
            if (z.getLocation().getBlock().isLiquid()) {
                z.getWorld().spawnParticle(Particle.SPLASH, z.getLocation().add(0, 0.5, 0), 2, 0.3, 0.1, 0.3, 0.1);
            }
        }
    }

    private List<Zombie> allDemons() {
        List<Zombie> out = new ArrayList<>();
        for (UUID id : demons.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Zombie z) out.add(z);
        }
        return out;
    }

    private void huntVillagersTick() {
        if (!plugin.getConfig().getBoolean("killVillagers", true)) return;
        for (Zombie z : allDemons()) {
            DemonState s = demons.get(z.getUniqueId());
            if (s != null && s.demonState == 1) {
                // Целься в ближайшего жителя, если игрок далеко
                Player target = Bukkit.getPlayer(s.targetId);
                if (target == null || target.getLocation().distance(z.getLocation()) > 64) {
                    LivingEntity v = nearestVillager(z);
                    if (v != null && z.getTarget() != v) z.setTarget(v);
                }
            }
        }
    }

    private LivingEntity nearestVillager(Zombie z) {
        LivingEntity best = null;
        double bestD = 48;
        for (Entity e : z.getWorld().getNearbyEntities(z.getLocation(), 48, 16, 48)) {
            if (e instanceof org.bukkit.entity.Villager v && !v.isDead()) {
                double d = v.getLocation().distance(z.getLocation());
                if (d < bestD) { bestD = d; best = v; }
            }
        }
        return best;
    }

    private boolean isLowCeiling(Location loc) {
        // Проход 2 блока → потолок над головой
        int x = loc.getBlockX(), y = loc.getBlockY(), zz = loc.getBlockZ();
        World w = loc.getWorld();
        if (w == null) return false;
        boolean feetFree = w.getBlockAt(x, y, zz).isEmpty() && w.getBlockAt(x, y + 1, zz).isEmpty();
        boolean headBlocked = !w.getBlockAt(x, y + 2, zz).isEmpty();
        return feetFree && headBlocked;
    }

    private void tickDemon(DemonState s, Zombie z, Player target) {
        Location dl = z.getLocation();
        double dist = dl.distance(target.getLocation());

        // Ест: стоит 3с, красные частицы/редстоун
        if (s.eating) {
            if (System.currentTimeMillis() < s.eatUntil) {
                spawnBloodParticles(z);
                z.setVelocity(new Vector(0, 0, 0));
                return;
            }
            s.eating = false;
        }

        // Взгляд → хруст → погоня
        if (s.demonState == 0 && isLookingAt(target, z)) {
            long now = System.currentTimeMillis();
            if (!stareStart.containsKey(z.getUniqueId())) {
                stareStart.put(z.getUniqueId(), now);
                crunchAt.put(z.getUniqueId(), now + 1000);
                z.getWorld().playSound(dl, Sound.BLOCK_BONE_BLOCK_STEP, (float) plugin.getConfig().getDouble("sounds.bone-crunch-volume", 1.5), (float) plugin.getConfig().getDouble("sounds.bone-crunch-pitch", 1.0));
            } else if (now >= crunchAt.get(z.getUniqueId())) {
                startChase(s, z, target);
                stareStart.remove(z.getUniqueId());
            }
            return;
        } else if (s.demonState == 0) {
            stareStart.remove(z.getUniqueId());
        }

        // Фаза окно: стоит за окном, исчезает от взгляда
        if (s.huntPhase == 0 && s.demonState == 0 && isLookingAt(target, z)) {
            // исчез в дыму
            dl.getWorld().playSound(dl, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            dl.getWorld().spawnParticle(Particle.SMOKE, dl, 20, 0.5, 0.5, 0.5, 0.1);
            Location away = target.getLocation().add(randVec(16, 32));
            away.setY(worldSurfaceY(away));
            z.teleport(away);
            return;
        }

        // Погоня: трек chase по кругу (громкость 3, pitch 0.6)
        if (s.demonState == 1) {
            chaseSoundTick(z, target);

            // Ломает стекло, листву, двери. Упираясь в стену — блоки над головой
            breakObstacles(z, target);

            // Лезет по стене, если игрок выше на 3+ блока
            if (target.getLocation().getY() - dl.getY() >= 3) {
                climb(z, target);
            }

            // Атака раз в секунду 50/50
            if (dist <= 3.0) attackTick(s, z, target);

            // Нет сна, когда демон в 64 блоках
            if (dist <= plugin.getConfig().getDouble("demon.sleep-danger-distance", 64)) {
                if (target.isSleeping()) {
                    target.wakeup(true);
                    target.sendMessage(Component.text("Он рядом. Спать нельзя.", NamedTextColor.DARK_RED));
                }
            }

            // Прыжок сквозь окно (главный jumpscare)
            windowJumpTick(s, z, target, dist);
        } else {
            // Наблюдает: стоит на ~32 блоках
            if (dist > 34 && dist < 200) {
                moveToward(z, target, 32);
            }
        }
    }

    private void chaseSoundTick(Zombie z, Player target) {
        float vol = (float) plugin.getConfig().getDouble("sounds.chase-volume", 3.0);
        float pitch = (float) plugin.getConfig().getDouble("sounds.chase-pitch", 0.6);
        // Цикл: играем звук погони рядом с демоном (клиентский loop нельзя — серверный приближённый)
        if (System.currentTimeMillis() % 4000 < 40) {
            target.playSound(z.getLocation(), Sound.ENTITY_WARDEN_ANGRY, vol, pitch);
            target.sendActionBar(Component.text(plugin.getConfig().getString("sounds.chase-subtitle", "RUN"), NamedTextColor.DARK_RED));
        }
    }

    private void breakObstacles(Zombie z, Player target) {
        Location dl = z.getLocation();
        World w = dl.getWorld();
        if (w == null) return;
        Vector dir = target.getLocation().toVector().subtract(dl.toVector()).normalize();
        // Блок перед демоном
        Location ahead = dl.clone().add(dir.clone().multiply(1.5));
        breakBlockIfBreakable(z, w, ahead);
        // Блок над головой при упоре в стену
        if (z.getVelocity().lengthSquared() < 0.01) {
            Location above = dl.clone().add(0, 2.5, 0).add(dir.clone().multiply(1.5));
            if (w.getBlockAt(above).getType().isSolid()) {
                breakBlockIfBreakable(z, w, above);
            }
        }
    }

    private void breakBlockIfBreakable(Zombie z, World w, Location loc) {
        Material m = w.getBlockAt(loc).getType();
        if (m == Material.GLASS || m == Material.GLASS_PANE || m == Material.OAK_DOOR || m == Material.SPRUCE_DOOR
                || m == Material.BIRCH_DOOR || m == Material.JUNGLE_DOOR || m == Material.ACACIA_DOOR
                || m == Material.DARK_OAK_DOOR || m == Material.MANGROVE_DOOR || m == Material.CHERRY_DOOR
                || m == Material.PALE_OAK_DOOR || m == Material.CRIMSON_DOOR || m == Material.WARPED_DOOR
                || m == Material.IRON_DOOR || m == Material.COPPER_DOOR || m == Material.OAK_LEAVES
                || m == Material.SPRUCE_LEAVES || m == Material.BIRCH_LEAVES || m == Material.JUNGLE_LEAVES) {
            w.getBlockAt(loc).breakNaturally();
            w.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
        }
    }

    private void climb(Zombie z, Player target) {
        // Поднимаемся к игроку
        Location want = target.getLocation();
        Vector v = want.toVector().subtract(z.getLocation().toVector()).normalize();
        z.setVelocity(v.multiply(0.28).setY(0.3));
        // Ломка блока под ногами при подъёме
        Location feet = z.getLocation();
        if (feet.getBlock().getType().isSolid()) {
            feet.getBlock().breakNaturally();
        }
    }

    private void moveToward(Zombie z, Player target, double stopDist) {
        double d = z.getLocation().distance(target.getLocation());
        if (d > stopDist) {
            Vector v = target.getLocation().toVector().subtract(z.getLocation().toVector()).normalize().multiply(0.2);
            z.setVelocity(v);
        }
    }

    /* ---------------- атака / захват / еда ---------------- */

    private void attackTick(DemonState s, Zombie z, Player target) {
        long now = System.currentTimeMillis();
        Map<UUID, Long> last = new HashMap<>();
        // Простое троттление через System.currentTimeMillis в map
        // Используем lastJumpScare как общую метку атаки? Нет — отдельный контроль
        // Для простоты: 50/50 каждые ~1с при дистанции <= 3
        double dist = z.getLocation().distance(target.getLocation());
        if (dist > 3.5) return;
        // Attack handled by Zombie native melee + grab override
        if (ThreadLocalRandom.current().nextBoolean()) {
            // Удар 19 урона — нативный мили зомби (attack_damage=19)
            z.setTarget(target);
        } else {
            grabTick(s, z, target);
        }
    }

    private void grabTick(DemonState s, Zombie z, Player target) {
        // Держит 38 тиков на высоте 2-3 блока, потом бросок x4 + 5 урона
        UUID victimId = target.getUniqueId();
        int ticks = grabTicks.getOrDefault(victimId, 0);
        int max = (int) (plugin.getConfig().getDouble("demon.grab-seconds", 1.9) * 20); // ~38
        if (ticks == 0) {
            grabVictims.put(victimId, target);
            target.setVelocity(new Vector(0, 0.5, 0));
        }
        Location dl = z.getLocation();
        Vector look = dl.getDirection();
        Location hold = dl.clone().add(look.clone().multiply(1.5)).add(0, 2 + Math.min(1, ticks / (double) max), 0);
        target.teleport(hold);
        target.setNoDamageTicks(0);
        if (++ticks >= max) {
            Vector dir = target.getLocation().toVector().subtract(dl.toVector()).normalize();
            double mult = plugin.getConfig().getDouble("demon.grab-throw-multiplier", 4.0);
            target.setVelocity(dir.multiply(mult).setY(1.2).add(new Vector(0, 0.2, 0)));
            target.damage(plugin.getConfig().getDouble("demon.grab-damage", 5.0), z);
            grabTicks.remove(victimId);
            grabVictims.remove(victimId);
        } else {
            grabTicks.put(victimId, ticks);
        }
    }

    /* ---------------- прыжок сквозь окно ---------------- */

    private void windowJumpTick(DemonState s, Zombie z, Player target, double dist) {
        if (dist > 4) return;
        long last = lastJumpScare.getOrDefault(z.getUniqueId(), 0L);
        if (System.currentTimeMillis() - last < plugin.getConfig().getLong("demon.jump-cooldown-ticks", 200L) * 50L) return;
        // Между вами стекло в пределах 4 блоков?
        Location between = z.getLocation().clone().add(target.getLocation().toVector().subtract(z.getLocation().toVector()).multiply(0.5));
        World w = between.getWorld();
        if (w == null) return;
        boolean hasGlass = false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
            Material m = w.getBlockAt(between.getBlockX() + dx, between.getBlockY() + dy, between.getBlockZ() + dz).getType();
            if (m == Material.GLASS || m == Material.GLASS_PANE) { hasGlass = true; break; }
        }
        if (!hasGlass) return;
        // Вышивает кусок 4x4x4 и прыгает
        int size = plugin.getConfig().getInt("demon.jump-break-size", 4);
        for (int dx = -size / 2; dx <= size / 2; dx++) for (int dy = 0; dy < size; dy++) for (int dz = -size / 2; dz <= size / 2; dz++) {
            Material m = w.getBlockAt(between.getBlockX() + dx, between.getBlockY() + dy, between.getBlockZ() + dz).getType();
            if (m == Material.GLASS || m == Material.GLASS_PANE) {
                w.getBlockAt(between.getBlockX() + dx, between.getBlockY() + dy, between.getBlockZ() + dz).setType(Material.AIR);
            }
        }
        // Прыжок на игрока + jumpscare 2.5с
        z.teleport(target.getLocation().clone().add(0, 1, 0));
        // Звук jumpscare — 2.5с: играем дважды
        target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1f, 0.5f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            target.playSound(target.getLocation(), Sound.ENTITY_WARDEN_ANGRY, 0.8f, 0.6f), 10L);
        lastJumpScare.put(z.getUniqueId(), System.currentTimeMillis());
    }

    /* ---------------- еда / смерть ---------------- */

    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent ev) {
        Player p = ev.getEntity();
        Entity killer = ev.getEntity().getKiller();
        if (killer != null && isDemon(killer)) {
            Zombie z = (Zombie) killer;
            DemonState s = demons.get(z.getUniqueId());
            if (s != null) {
                s.eating = true;
                s.eatUntil = System.currentTimeMillis() + plugin.getConfig().getInt("demon.eat-seconds", 3) * 1000L;
            }
            spawnBloodParticles(z);
            if (plugin.getConfig().getBoolean("canCrash", true)) {
                p.kick(Component.text("Farewell, " + p.getName(), NamedTextColor.DARK_RED, net.kyori.adventure.text.format.TextDecoration.BOLD));
            }
        }
    }

    private void spawnBloodParticles(Zombie z) {
        Location dl = z.getLocation();
        dl.getWorld().spawnParticle(Particle.CRIT, dl.clone().add(0, 1.5, 0), 10, 0.5, 0.5, 0.5, 0.2);
        dl.getWorld().spawnParticle(Particle.DRIPPING_DRIPSTONE_WATER, dl.clone().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.1);
        // Редстоун-пыль как кровь — REDSTONE частицы
        try {
            // REDSTONE particle removed for Paper 26.3 API
            dl.getWorld().spawnParticle(Particle.CRIT, dl.clone().add(0, 1.5, 0), 15, 0.6, 0.6, 0.6, 0.1);
        } catch (Exception ignored) {}
    }

    /* ---------------- взгляд / погоня ---------------- */

    boolean isLookingAt(Player p, LivingEntity d) {
        Vector view = p.getLocation().getDirection().normalize();
        Vector to = new Vector(d.getLocation().getX() - p.getLocation().getX(),
                d.getLocation().getY() + 1.5 - p.getLocation().getY(),
                d.getLocation().getZ() - p.getLocation().getZ()).normalize();
        return view.dot(to) > 0.98 && hasLineOfSight(p, d);
    }

    private boolean hasLineOfSight(Player p, LivingEntity d) {
        // Стекло и листва не мешают — rayTrace игнорируя их
        Location from = p.getEyeLocation();
        Vector dir = from.getDirection().normalize();
        double dist = from.distance(d.getLocation());
        Location cur = from.clone();
        for (double t = 0; t < dist; t += 0.5) {
            cur = from.clone().add(dir.clone().multiply(t));
            Material m = cur.getBlock().getType();
            if (m == Material.GLASS || m == Material.GLASS_PANE || m.toString().contains("LEAVES")) continue;
            if (m.isSolid()) return false;
        }
        return true;
    }

    private void startChase(DemonState s, Zombie z, Player target) {
        s.demonState = 1;
        s.huntPhase = 1;
        z.setTarget(target);
        target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 1200, 0, false, false, false));
        z.getWorld().playSound(z.getLocation(), Sound.BLOCK_BONE_BLOCK_STEP,
                (float) plugin.getConfig().getDouble("sounds.bone-crunch-volume", 1.5),
                (float) plugin.getConfig().getDouble("sounds.bone-crunch-pitch", 1.0));
    }

    /* ---------------- окно (PlayerTickEvent) ---------------- */

    /** Срабатывает с 5-го дня мира, один раз на игрока. Вызывается из VerityPlugin. */
    public void windowScareTick(Player player) {
        if (player.getScoreboardTags().contains(TAG_WINDOW_DONE)) return;
        int dayCount = plugin.getConfig().getInt("dayCount", 5);
        long dayTime = player.getWorld().getFullTime();
        long day = dayTime / 24000L + 1;
        if (day < dayCount) return;
        if (player.getScoreboardTags().contains("verity_transformed")) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        if (player.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) return;
        // Один раз в 5с проверка через modulo — упрощённо: шанс
        if (player.getWorld().getFullTime() % 100 != 0) return;
        Location pl = player.getLocation();
        World world = player.getWorld();
        Location demonLoc;
        int huntPhase;
        boolean canSeeSky = world.getHighestBlockAt(pl.getBlockX(), pl.getBlockZ()).getY() <= pl.getBlockY();
        if (!canSeeSky) {
            // В помещении (небо не видно)
            demonLoc = findGlassNear(player, 5);
            if (demonLoc == null) return;
            huntPhase = 0;
        } else {
            // На улице: за спиной 24 блока
            Vector back = pl.getDirection().normalize().multiply(-24);
            demonLoc = pl.clone().add(back);
            huntPhase = 1;
        }
        demonLoc.setY(worldSurfaceY(demonLoc));
        Zombie z = spawnDemon(player, demonLoc, huntPhase);
        if (z == null) return;
        // ТЗ sec 1,6: только владельцу — персональная полночь, мир/гейтрул не трогаем
        player.setPlayerTime(18000, false);
        if (!player.getScoreboardTags().contains("verity_transformed")) {
            player.getScoreboardTags().add("verity_transformed");
            player.getScoreboardTags().add("verity_transformDone");
        }
        player.getWorld().playSound(demonLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        player.addScoreboardTag(TAG_WINDOW_DONE);
        try { manager.setPhase(player, VerityPhase.NOFACE); } catch (Exception ignored) {}
        try { manager.setMonstrous(player, true); } catch (Exception ignored) {}
        try { manager.face(player, FaceKind.NOFACE); } catch (Exception ignored) {}
        manager.speakTo(player, "За окном кто-то стоит. Не смотри долго.");
    }

    private Location findGlassNear(Player player, int radius) {
        Location base = player.getLocation();
        World w = player.getWorld();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) for (int dy = -1; dy <= 3; dy++) {
            Location l = base.clone().add(dx, dy, dz);
            Material m = w.getBlockAt(l).getType();
            if (m == Material.GLASS || m == Material.GLASS_PANE) return l.clone().add(0, 0, 0);
        }
        return null;
    }

    private double worldSurfaceY(Location loc) {
        World w = loc.getWorld();
        if (w == null) return loc.getY();
        return w.getHighestBlockAt(loc.getBlockX(), loc.getBlockZ()).getY() + 1;
    }

    private Vector randVec(double min, double max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble(Math.PI * 2);
        double d = min + r.nextDouble(max - min);
        return new Vector(Math.cos(a) * d, 0, Math.sin(a) * d);
    }

    /* ---------------- трансформация / хорошая концовка ---------------- */

    /** Демон убит → хорошая концовка если есть успокоенный шар. */
    public void onDemonDeath(org.bukkit.event.entity.EntityDeathEvent ev) {
        if (!(ev.getEntity() instanceof Zombie z) || !isDemon(z)) return;
        removeRig(z.getUniqueId());
        demons.remove(z.getUniqueId());
        for (Player p : z.getWorld().getPlayers()) {
            if (p.getLocation().distance(z.getLocation()) < 64 && manager.hasVerity(p)) {
                try { p.resetPlayerTime(); } catch (Exception ignored) {}
                try { p.resetPlayerWeather(); } catch (Exception ignored) {}
                try { p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, z.getLocation().add(0, 1, 0), 100, 0.5, 0.5, 0.5, 0.1); } catch (Exception ignored) {}
                try { p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_TOTEM_USE, 1f, 1f); } catch (Exception ignored) {}
                try { manager.setMonstrous(p, false); } catch (Exception ignored) {}
                try { manager.setPhase(p, VerityPhase.CALM); } catch (Exception ignored) {}
                try { manager.face(p, FaceKind.HAPPY); } catch (Exception ignored) {}
                manager.setKarma(p, plugin.getConfig().getInt("karma.good-ending", 20));
                p.getScoreboardTags().remove("verity_transformed");
                p.getScoreboardTags().remove("verity_transformDone");
                manager.speakTo(p, plugin.getConfig().getString("replies.good-ending", "The darkness... it's gone. Thank you."));
            }
        }
    }

    public boolean anyDemonNear(Location loc, double radius) {
        for (Zombie z : allDemons()) {
            if (z.getWorld().equals(loc.getWorld()) && z.getLocation().distance(loc) <= radius) return true;
        }
        return false;
    }
}
