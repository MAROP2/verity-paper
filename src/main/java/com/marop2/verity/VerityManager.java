package com.marop2.verity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the per-player Verity instances. A single repeating task (every 2 ticks)
 * drives movement, bobbing, facing, dialogue reveals, particles, sounds and
 * phase escalation for every active Verity.
 */
public final class VerityManager {

    /** Face drawn above the sphere: black smile characters. */
    private static final String FACE_TEXT = "\u25D4\u203F\u25D5"; // ◔‿◕

    private final JavaPlugin plugin;
    private final DialogueManager dialogue;

    private final Map<UUID, VerityInstance> instances = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> karma = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> sorryCounts = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> idleSayAt = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> aloneLastWarn = new ConcurrentHashMap<>();

    private final double scale;
    private final double bobAmplitude;
    private final double bobSpeed;
    private final double hoverAbove;
    private final int letterRevealTicks;
    private final double bubbleHeight;
    private final float caveVolume;
    private final float witchVolume;
    private final double darknessChance;
    private final int darknessDuration;

    public record PhaseSettings(double distance, int duration) {}

    public VerityManager(JavaPlugin plugin, DialogueManager dialogue) {
        this.plugin = plugin;
        this.dialogue = dialogue;
        var cfg = plugin.getConfig();
        this.scale = cfg.getDouble("visual.scale", 0.5);
        this.bobAmplitude = cfg.getDouble("visual.bob-amplitude", 0.15);
        this.bobSpeed = cfg.getDouble("visual.bob-speed", 0.12);
        this.hoverAbove = cfg.getDouble("visual.hover-above", 2.5);
        this.letterRevealTicks = Math.max(1, cfg.getInt("visual.letter-reveal-ticks", 3));
        this.bubbleHeight = cfg.getDouble("visual.bubble-height", 1.1);
        this.caveVolume = (float) cfg.getDouble("sounds.cave-ambient-volume", 0.35);
        this.witchVolume = (float) cfg.getDouble("sounds.witch-ambient-volume", 0.4);
        this.darknessChance = cfg.getDouble("phases.stalking.darkness-chance", 0.005);
        this.darknessDuration = cfg.getInt("phases.stalking.darkness-duration", 100);

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 2L, 2L);
    }

    /* ------------------------------------------------------------------ */
    /* Config helpers                                                     */
    /* ------------------------------------------------------------------ */

    public PhaseSettings settings(VerityPhase phase) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("phases." + phase.name().toLowerCase());
        if (sec == null) {
            return new PhaseSettings(phase == VerityPhase.CALM ? 5.0
                    : phase == VerityPhase.NOTICING ? 3.0 : 1.5, 300);
        }
        double dist = switch (phase) {
            case CALM -> sec.getDouble("distance", 5.0);
            case NOTICING -> sec.getDouble("distance", 3.0);
            case STALKING -> sec.getDouble("distance", 1.5);
            default -> 1.5;
        };
        int dur = Math.max(1, sec.getInt("duration", 300));
        return new PhaseSettings(dist, dur);
    }

    /* ------------------------------------------------------------------ */
    /* Spawn / despawn                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Spawns Verity for the target player at a random point within maxDistance blocks.
     */
    public void spawn(Player target, int maxDistance) {
        if (hasVerity(target)) return;
        World world = target.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        Location base = target.getLocation();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = maxDistance * (0.5 + rnd.nextDouble() * 0.5);
        int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
        int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
        Location spawnLoc = world.getHighestBlockAt(x, z).getLocation().add(0.5, 1, 0.5);
        spawnLoc.setY(base.getY() + hoverAbove);

        spawnAt(target, spawnLoc);
    }

    /** Spawns Verity at an exact location for the target player. */
    public void spawnAt(Player target, Location loc) {
        if (hasVerity(target)) despawn(target);
        World world = loc.getWorld();
        if (world == null) return;

        // --- The sphere: yellow wool ItemDisplay, FIXED billboard, ~0.5 scale ---
        ItemDisplay sphere = (ItemDisplay) world.spawnEntity(loc, EntityType.ITEM_DISPLAY);
        sphere.setItemStack(new ItemStack(Material.YELLOW_WOOL));
        sphere.setBillboard(Display.Billboard.FIXED);
        org.joml.Vector3f s = sphere.getTransformation().getScale();
        s.set(scale, scale, scale);
        sphere.setTeleportDuration(2); // smooth interpolation of our 2-tick updates
        sphere.setPersistent(false);
        sphere.setViewRange(1.0f);
        sphere.setShadowRadius(0f);

        // --- Face: black smile characters above the sphere ---
        Location faceLoc = loc.clone().add(0, 0.55, 0);
        TextDisplay face = (TextDisplay) world.spawnEntity(faceLoc, EntityType.TEXT_DISPLAY);
        face.text(Component.text(FACE_TEXT, TextColor.color(0x000000)).decoration(TextDecoration.BOLD, true));
        face.setBillboard(Display.Billboard.CENTER);
        face.setTransformation(sphere.getTransformation());
        face.setSeeThrough(false);
        face.setShadowed(false);
        face.setDefaultBackground(false);
        face.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        face.setPersistent(false);
        face.setTeleportDuration(2);

        // --- Speech bubble above the face ---
        Location bubbleLoc = loc.clone().add(0, bubbleHeight, 0);
        TextDisplay bubble = (TextDisplay) world.spawnEntity(bubbleLoc, EntityType.TEXT_DISPLAY);
        bubble.text(Component.text("", NamedTextColor.WHITE));
        bubble.setBillboard(Display.Billboard.CENTER);
        bubble.setSeeThrough(false);
        bubble.setShadowed(false);
        bubble.setLineWidth(200);
        bubble.setBackgroundColor(org.bukkit.Color.fromARGB(120, 0, 0, 0));
        bubble.setPersistent(false);
        bubble.setTeleportDuration(2);

        karma.put(target.getUniqueId(), plugin.getConfig().getInt("karma.start", 10));
        VerityInstance inst = new VerityInstance(target.getUniqueId(), sphere, face, bubble);
        inst.phase = VerityPhase.CALM;
        inst.phaseTicksRemaining = settings(VerityPhase.CALM).duration();
        instances.put(target.getUniqueId(), inst);
        speak(inst, dialogue.randomPhrase(VerityPhase.CALM, target.getName()));
    }

    public void despawn(Player target) {
        VerityInstance inst = instances.remove(target.getUniqueId());
        if (inst != null) inst.removeEntities();
    }

    public void despawnAll() {
        for (VerityInstance inst : instances.values()) inst.removeEntities();
        instances.clear();
    }

    public boolean hasVerity(Player target) {
        return instances.containsKey(target.getUniqueId());
    }

    public int karmaOf(Player target) {
        return karma.getOrDefault(target.getUniqueId(), plugin.getConfig().getInt("karma.default", 10));
    }

    public void setKarma(Player target, int v) {
        karma.put(target.getUniqueId(), Math.max(plugin.getConfig().getInt("karma.min", 0), Math.min(plugin.getConfig().getInt("karma.max", 20), v)));
    }

    public void addKarma(Player target, int delta) {
        setKarma(target, karmaOf(target) + delta);
    }

    public VerityPhase phaseOf(Player target) {
        VerityInstance inst = instances.get(target.getUniqueId());
        return inst == null ? null : inst.phase();
    }

    public void speakTo(Player target, String phase) {
        VerityInstance inst = instances.get(target.getUniqueId());
        if (inst != null) speak(inst, phase);
        target.sendMessage(Component.text("Verity: " + phase, NamedTextColor.YELLOW));
    }

    public void speakPhase(Player target, VerityPhase phase) {
        speakTo(target, dialogue.randomPhrase(phase, target.getName()));
    }

    /** UIManager: пройти условия ^ 10 30б. */
    public boolean isLookingAtBall(Player p, VerityInstance inst) {
        org.bukkit.Location from = p.getEyeLocation();
        org.bukkit.Location ball = inst.sphere().getLocation();
        Vector view = from.getDirection().normalize();
        Vector to = new Vector(ball.getX() - from.getX(), ball.getY() - from.getY(), ball.getZ() - from.getZ()).normalize();
        return view.dot(to) > 0.98 && from.distance(ball) <= 10;
    }

    public void onSorry(Player p) {
        long c = sorryCounts.getOrDefault(p.getUniqueId(), 0L) + 1;
        sorryCounts.put(p.getUniqueId(), c);
        if (c >= 3) sorryCounts.put(p.getUniqueId(), 0L);
    }

    public boolean sorryOk(Player p) {
        return sorryCounts.getOrDefault(p.getUniqueId(), 0L) >= 3;
    }

    public void face(Player p, FaceKind kind) {
        VerityInstance inst = instances.get(p.getUniqueId());
        if (inst != null) inst.face().text(Component.text(kind.text(), net.kyori.adventure.text.format.TextColor.color(0x000000)).decoration(TextDecoration.BOLD, true));
    }


    /** Force-despawn any Verity whose entities live in a chunk being unloaded. */
    public void handleChunkUnload(Chunk chunk) {
        Iterator<Map.Entry<UUID, VerityInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            VerityInstance inst = it.next().getValue();
            for (Entity e : inst.entities()) {
                if (!e.isValid()) continue;
                Chunk c = e.getLocation().getChunk();
                if (c == chunk || (c.getX() == chunk.getX() && c.getZ() == chunk.getZ()
                        && c.getWorld().equals(chunk.getWorld()))) {
                    inst.removeEntities();
                    it.remove();
                    break;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Phase control                                                      */
    /* ------------------------------------------------------------------ */

    public void setPhase(Player target, VerityPhase phase) {
        VerityInstance inst = instances.get(target.getUniqueId());
        if (inst == null) return;
        inst.phase = phase;
        inst.phaseTicksRemaining = settings(phase).duration();
        if (phase == VerityPhase.JUMPSCARE) {
            doJumpscare(inst);
        } else {
            Player p = plugin.getServer().getPlayer(inst.targetId());
            if (p != null) speak(inst, dialogue.randomPhrase(phase, p.getName()));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Main tick (runs every 2 ticks)                                     */
    /* ------------------------------------------------------------------ */

    private void tickAll() {
        Iterator<Map.Entry<UUID, VerityInstance>> it = instances.entrySet().iterator();
        while (it.hasNext()) {
            VerityInstance inst = it.next().getValue();
            Player target = plugin.getServer().getPlayer(inst.targetId());
            if (target == null || !target.isOnline() || !inst.isAlive()) {
                inst.removeEntities();
                it.remove();
                continue;
            }
            tickInstance(inst, target);
        }
    }

    private void tickInstance(VerityInstance inst, Player target) {
        inst.ticks += 2;
        long tick = inst.ticks;

        // --- Phase escalation timer ---
        if (inst.phase != VerityPhase.JUMPSCARE) {
            inst.phaseTicksRemaining -= 2;
            if (inst.phaseTicksRemaining <= 0) {
                VerityPhase next = switch (inst.phase) {
                    case CALM -> VerityPhase.NOTICING;
                    case NOTICING -> VerityPhase.STALKING;
                    case STALKING -> VerityPhase.JUMPSCARE;
                    default -> inst.phase;
                };
                setPhase(target, next);
                if (inst.phase == VerityPhase.JUMPSCARE) return;
            }
        }

        Location targetLoc0 = target.getLocation();
        Location sphereLoc0 = inst.sphere().getLocation();
        // --- Karma: лицо по карме (серьёзные 13+/20) ---
        int k = karmaOf(target);
        FaceKind fk = FaceKind.forKarma(k);
        if (inst.phase() != VerityPhase.NOFACE && inst.phase() != VerityPhase.JUMPSCARE) {
            try { inst.face().text(Component.text(fk.text(), net.kyori.adventure.text.format.TextColor.color(0x000000)).decoration(TextDecoration.BOLD, true)); } catch (Exception ignored) {}
        }

        // --- Одиночество: >32 блоков — "I'm alone..." и -1 карма/сек ---
        double distToPlayer = sphereLoc0.distance(targetLoc0);
        if (distToPlayer > 32) {
            long nowMs = System.currentTimeMillis();
            long lastWarn = aloneLastWarn.getOrDefault(target.getUniqueId(), 0L);
            if (nowMs - lastWarn > 1000) {
                aloneLastWarn.put(target.getUniqueId(), nowMs);
                addKarma(target, plugin.getConfig().getInt("karma.alone-delta", -1));
                if (nowMs - lastWarn > 5000) speak(inst, dialogue.reply("alone", target.getName()));
            }
        }

        // --- Sam по себе: заговаривает каждые 2-4 мин ---
        long now = System.currentTimeMillis();
        long idleAt = idleSayAt.getOrDefault(target.getUniqueId(), now);
        if (now - idleAt > 120000 && now - idleAt < 240000) {
            idleSayAt.put(target.getUniqueId(), now);
            speak(inst, dialogue.randomReply("idle", target.getName()));
        }

        // --- Movement: hover toward a point `phaseDistance` from the player,
        //     `hoverAbove` blocks above their feet, with sin-wave bobbing. ---
        PhaseSettings ps = settings(inst.phase);
        Location targetLoc = target.getLocation();
        Location sphereLoc = inst.sphere().getLocation();

        Vector toVerity = sphereLoc.toVector().subtract(targetLoc.toVector());
        toVerity.setY(0);
        if (toVerity.lengthSquared() < 1.0e-4) {
            toVerity = new Vector(1, 0, 0);
        }
        Vector desired = toVerity.normalize().multiply(ps.distance());
        double bob = Math.sin(tick * bobSpeed) * bobAmplitude;
        Location newLoc = targetLoc.clone().add(desired)
                .add(0, hoverAbove + bob, 0);

        inst.sphere().teleport(newLoc);
        inst.face().teleport(newLoc.clone().add(0, 0.55, 0));
        Location bubbleLoc = newLoc.clone().add(0, bubbleHeight, 0);
        inst.bubble().teleport(bubbleLoc);

        // --- Face the player: yaw/pitch toward the target. ---
        Vector eyeToPlayer = target.getEyeLocation().toVector().subtract(newLoc.toVector());
        Location look = newLoc.clone().setDirection(eyeToPlayer);
        float yaw = look.getYaw();
        float pitch = look.getPitch();
        inst.sphere().setRotation(yaw, pitch);

        // --- Letter-by-letter speech reveal ---
        if (inst.revealTotal() > 0 && tick >= inst.nextRevealTick()) {
            inst.setRevealed(Math.min(inst.revealTotal(), inst.revealed() + 1));
            inst.setNextRevealTick(tick + letterRevealTicks);
            inst.bubble().text(Component.text(inst.phrase().substring(0, inst.revealed()), NamedTextColor.WHITE));
        }

        // --- Glowing eyes: sparse ELECTRIC_SPARK particles at eye height ---
        if (tick % 10 == 0) {
            Location eyes = newLoc.clone().add(0, 0.35, 0);
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, eyes, 3, 0.12, 0.06, 0.12, 0.005);
        }

        // --- Creepy ambience per phase ---
        if (inst.phase == VerityPhase.STALKING) {
            if (tick % 200 == 0) { // ~every 10s
                target.playSound(target, Sound.AMBIENT_CAVE, caveVolume, 0.7f);
            }
            if (tick % 90 == 0 && tick % 180 != 0) {
                target.getWorld().playSound(newLoc, Sound.ENTITY_WITCH_AMBIENT, witchVolume, 0.6f);
            }
            if (ThreadLocalRandom.current().nextDouble() < darknessChance) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darknessDuration, 0, false, false));
            }
        } else if (inst.phase == VerityPhase.NOTICING && tick % 140 == 0) {
            target.getWorld().playSound(newLoc, Sound.ENTITY_WITCH_AMBIENT, witchVolume * 0.7f, 0.65f);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Jumpscare                                                          */
    /* ------------------------------------------------------------------ */

    private void doJumpscare(VerityInstance inst) {
        Player target = plugin.getServer().getPlayer(inst.targetId());
        if (target == null) {
            inst.removeEntities();
            instances.remove(inst.targetId());
            return;
        }
        Location loc = inst.sphere().getLocation();

        // Enderman stare directly at the player.
        target.playSound(target, Sound.ENTITY_ENDERMAN_STARE, 1.0f, 0.6f);

        // "Monster eyes": dense particle burst of sparks around the face.
        Location eyes = loc.clone().add(0, 0.4, 0);
        target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, eyes, 60, 0.4, 0.3, 0.4, 0.15);
        target.getWorld().spawnParticle(Particle.SMALL_FLAME, eyes, 20, 0.3, 0.25, 0.3, 0.02);

        // The face goes hollow.
        inst.face().text(Component.text("\u25CF\u203F\u25CF", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.BOLD, true));

        // Vanish after a short beat.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            inst.removeEntities();
            instances.remove(inst.targetId());
        }, 15L);

        // Chat flavor text.
        target.sendMessage(Component.text(dialogue.jumpscareFlavor(target.getName()), NamedTextColor.DARK_RED)
                .decoration(TextDecoration.BOLD, true));
    }

    /* ------------------------------------------------------------------ */
    /* Dialogue                                                           */
    /* ------------------------------------------------------------------ */

    private void speak(VerityInstance inst, String phrase) {
        inst.setPhrase(phrase);
        inst.setRevealed(0);
        inst.setRevealTotal(phrase.length());
        inst.setNextRevealTick(inst.ticks + 10);
        inst.bubble().text(Component.empty());
    }

    /* ------------------------------------------------------------------ */
    /* Per-player state                                                   */
    /* ------------------------------------------------------------------ */

    public static final class VerityInstance {
        private final UUID targetId;
        private final ItemDisplay sphere;
        private final TextDisplay face;
        private final TextDisplay bubble;

        VerityPhase phase = VerityPhase.CALM;
        int phaseTicksRemaining;
        long ticks;

        private String phrase = "";
        private int revealed;
        private int revealTotal;
        private long nextRevealTick;

        VerityInstance(UUID targetId, ItemDisplay sphere, TextDisplay face, TextDisplay bubble) {
            this.targetId = targetId;
            this.sphere = sphere;
            this.face = face;
            this.bubble = bubble;
        }

        UUID targetId() { return targetId; }
        ItemDisplay sphere() { return sphere; }
        TextDisplay face() { return face; }
        TextDisplay bubble() { return bubble; }

        public VerityPhase phase() { return phase; }

        boolean isAlive() {
            return sphere.isValid() && face.isValid() && bubble.isValid();
        }

        void removeEntities() {
            for (Entity e : new Entity[]{sphere, face, bubble}) {
                if (e.isValid()) e.remove();
            }
        }

        Entity[] entities() { return new Entity[]{sphere, face, bubble}; }

        String phrase() { return phrase; }
        void setPhrase(String p) { this.phrase = p == null ? "" : p; }
        int revealed() { return revealed; }
        void setRevealed(int r) { this.revealed = r; }
        int revealTotal() { return revealTotal; }
        void setRevealTotal(int t) { this.revealTotal = t; }
        long nextRevealTick() { return nextRevealTick; }
        void setNextRevealTick(long t) { this.nextRevealTick = t; }
    }
}
