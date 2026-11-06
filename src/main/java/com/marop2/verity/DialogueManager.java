package com.marop2.verity;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pulls phase-specific dialogue lines from config.yml and applies the %player% placeholder.
 */
public class DialogueManager {

    private final JavaPlugin plugin;

    public DialogueManager(JavaPlugin plugin) {
        this.plugin = plugin;
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
        List<String> raw = plugin.getConfig().getStringList(key);
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s);
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
        String raw = plugin.getConfig().getString("replies." + key, "");
        return raw.replace("%player%", playerName);
    }

    public String randomReply(String key, String playerName) {
        List<String> raw = plugin.getConfig().getStringList("replies." + key);
        if (raw == null || raw.isEmpty()) return reply(key, playerName);
        String r = raw.get(ThreadLocalRandom.current().nextInt(raw.size()));
        return r.replace("%player%", playerName);
    }
}
