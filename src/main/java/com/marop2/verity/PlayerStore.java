package com.marop2.verity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Персистентность игроков (ТЗ sec 3): plugins/Verity/players/&lt;uuid&gt;.json.
 * Атомарная запись (tmp + ATOMIC_MOVE), автосейв раз в 5 минут, синхронно
 * в onDisable. Карма, playTicks, dayOffset, флаги.
 */
public final class PlayerStore {
    public static final int SCHEMA = 1;

    public static final class Data {
        public int schema = SCHEMA;
        public String uuid = "";
        public boolean started = false;
        public long playTicks = 0L;          // онлайн-тики с момента старта
        public long dayOffset = 0L;          // коррекция админом
        public int karma = 10;               // 0..20
        public boolean transformDone = false;
        public boolean demonDefeated = false;
        public boolean purgeDone = false;
        public boolean windowScareDone = false;
    }

    private final VerityPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();

    public PlayerStore(VerityPlugin plugin) {
        this.plugin = plugin;
        File dir = dir();
        if (!dir.exists()) dir.mkdirs();
        // автосейв раз в 5 минут
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::saveAll, 6000L, 6000L);
    }

    private File dir() {
        return new File(plugin.getDataFolder(), "players");
    }

    private File file(UUID id) {
        return new File(dir(), id + ".json");
    }

    /** Загрузить данные игрока (кэш или диск, синхронно — вызывать из main при join). */
    public Data get(UUID id) {
        Data d = cache.get(id);
        if (d != null) return d;
        d = load(id);
        cache.put(id, d);
        return d;
    }

    public Data get(Player p) { return get(p.getUniqueId()); }

    private Data load(UUID id) {
        File f = file(id);
        if (!f.exists()) {
            Data d = new Data();
            d.uuid = id.toString();
            return d;
        }
        try {
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Data d = gson.fromJson(json, Data.class);
            if (d == null) d = new Data();
            if (d.schema != SCHEMA) d.schema = SCHEMA;
            if (d.uuid == null || d.uuid.isBlank()) d.uuid = id.toString();
            return d;
        } catch (Exception e) {
            plugin.getLogger().warning("PlayerStore: не удалось прочитать " + f.getName() + ": " + e.getMessage());
            Data d = new Data();
            d.uuid = id.toString();
            return d;
        }
    }

    /** Сохранить одного игрока атомарно. */
    public void save(UUID id) {
        Data d = cache.get(id);
        if (d == null) return;
        try {
            dir().mkdirs();
            Path target = file(id).toPath();
            Path tmp = target.resolveSibling(id + ".json.tmp");
            Files.writeString(tmp, gson.toJson(d), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            plugin.getLogger().warning("PlayerStore: не удалось сохранить " + id + ": " + e.getMessage());
        }
    }

    public void save(Player p) { save(p.getUniqueId()); }

    public void saveAll() {
        for (UUID id : cache.keySet()) save(id);
    }

    /** Выгрузить из кэша с сохранением (выход игрока). */
    public void unload(UUID id) {
        save(id);
        cache.remove(id);
    }

    /** День игрока: playTicks / dayLength + offset + 1. */
    public long playDay(UUID id) {
        Data d = get(id);
        long len = plugin.getConfig().getLong("days.length-ticks", 24000L);
        return d.playTicks / Math.max(1, len) + d.dayOffset + 1;
    }

    /** Тик счётчика онлайн-времени. */
    public void tickPlay(Player p) {
        Data d = get(p.getUniqueId());
        d.playTicks++;
    }

    public int karma(Player p) {
        return get(p.getUniqueId()).karma;
    }

    public void setKarma(Player p, int v) {
        Data d = get(p.getUniqueId());
        d.karma = Math.max(0, Math.min(20, v));
    }
}
