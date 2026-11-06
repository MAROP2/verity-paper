package com.marop2.verity;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pulls phase-specific dialogue lines from messages_<language>.yml (ТЗ sec 9)
 * with fallback to config.yml, and applies the %player% placeholder.
 */
public class DialogueManager {

    private final JavaPlugin plugin;
    private final YamlConfiguration messages;

    public DialogueManager(JavaPlugin plugin) {
        this.plugin = plugin;
        String lang = plugin.getConfig().getString("language", "ru");
        String fileName = "messages_" + (lang == null || lang.isBlank() ? "ru" : lang) + ".yml";
        File f = new File(plugin.getDataFolder(), fileName);
        if (!f.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException noResource) {
                // нет такого ресурса — работаем на config.yml
            }
        }
        this.messages = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
    }

    private List<String> list(String key) {
        List<String> fromMsg = messages.getStringList(key);
        if (fromMsg != null && !fromMsg.isEmpty()) return fromMsg;
        return plugin.getConfig().getStringList(key);
    }

    private String single(String key, String def) {
        String fromMsg = messages.getString(key, "");
        if (fromMsg != null && !fromMsg.isBlank()) return fromMsg;
        return plugin.getConfig().getString(key, def);
    }

    /** Random phrase for the given phase with %player% replaced. */
    public String randomPhrase(VerityPhase phase, String playerName) {
        List<String> phrases = phrases(phase);
        if (phrases.isEmpty()) {
            return "";
        }
        String raw = phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
        return raw.replace("%player%", playerName);
    }

    public List<String> phrases(VerityPhase phase) {
        String key = "phases." + phase.name().toLowerCase() + ".phrases";
        List<String> raw = list(key);
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s);
        }
        // script.<стадия>.idle из messages — тоже годится как реплика фазы
        if (out.isEmpty()) {
            String stage = switch (phase) {
                case CALM -> "friendly";
                case NOTICING -> "unsettling";
                case STALKING -> "glitch";
                case HOSTILE -> "hostile";
                case NOFACE -> "monstrous";
                default -> "friendly";
            };
            out = list("script." + stage + ".idle");
        }
        return out;
    }

    /** The fixed jumpscare flavor line sent to chat. */
    public String jumpscareFlavor(String playerName) {
        List<String> phrases = phrases(VerityPhase.JUMPSCARE);
        String raw = phrases.isEmpty() ? "ТЫ УВИДЕЛ МЕНЯ" : phrases.get(0);
        return raw.replace("%player%", playerName);
    }

    public String reply(String key, String playerName) {
        String raw = single("lines." + key, "");
        if (raw.isBlank()) raw = plugin.getConfig().getString("replies." + key, "");
        return raw.replace("%player%", playerName);
    }

    public String randomReply(String key, String playerName) {
        List<String> raw = list("replies." + key);
        if (raw == null || raw.isEmpty()) return reply(key, playerName);
        String r = raw.get(ThreadLocalRandom.current().nextInt(raw.size()));
        return r.replace("%player%", playerName);
    }

    /** Реплика стадии из script.<стадия>.reply (режим без ИИ). */
    public String scriptReply(String stage, String playerName, int daysLeft) {
        List<String> raw = list("script." + stage + ".reply");
        if (raw == null || raw.isEmpty()) return "";
        String r = raw.get(ThreadLocalRandom.current().nextInt(raw.size()));
        return r.replace("%player%", playerName).replace("<days>", String.valueOf(Math.max(0, daysLeft)));
    }
}
