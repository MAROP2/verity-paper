package com.marop2.verity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

public final class VerityPlugin extends JavaPlugin implements Listener {

    private VerityManager manager;
    private DialogueManager dialogue;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.dialogue = new DialogueManager(this);
        this.manager = new VerityManager(this, dialogue);
        getServer().getPluginManager().registerEvents(this, this);
        VerityCommand cmd = new VerityCommand(manager);
        if (getCommand("verity") != null) {
            getCommand("verity").setExecutor(cmd);
            getCommand("verity").setTabCompleter(cmd);
        }
        getLogger().info("Verity is watching. Always.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.despawnAll();
        }
        if (getServer().getScheduler().isCurrentlyRunning(-1)) {
            // no-op; scheduler cancels tasks automatically on disable
        }
    }

    public VerityManager getManager() {
        return manager;
    }

    public DialogueManager getDialogue() {
        return dialogue;
    }

    /* ------------------------------------------------------------------ */
    /* Listeners                                                          */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("auto-spawn.enabled", true)) return;
        double chance = getConfig().getDouble("auto-spawn.chance", 0.3);
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;

        int min = getConfig().getInt("auto-spawn.min-delay-seconds", 30);
        int max = getConfig().getInt("auto-spawn.max-delay-seconds", 120);
        int delay = ThreadLocalRandom.current().nextInt(min, max + 1) * 20;

        Player player = event.getPlayer();
        int maxDist = getConfig().getInt("auto-spawn.max-distance", 10);
        getServer().getScheduler().runTaskLater(this, () -> {
            // Player may have quit during the delay.
            if (!player.isOnline()) return;
            if (manager.hasVerity(player)) return;
            manager.spawn(player, maxDist);
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.despawn(event.getPlayer());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.handleChunkUnload(event.getChunk());
    }
}
