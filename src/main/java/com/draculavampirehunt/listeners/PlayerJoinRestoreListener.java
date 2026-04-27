package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinRestoreListener implements Listener {

    private final DraculaVampireHunt plugin;

    public PlayerJoinRestoreListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (player == null || manager == null) {
            return;
        }

        manager.handlePlayerJoin(player);
    }
}