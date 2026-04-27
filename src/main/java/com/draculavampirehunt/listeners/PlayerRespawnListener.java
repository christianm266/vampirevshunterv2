package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerRespawnListener implements Listener {

    private final DraculaVampireHunt plugin;

    public PlayerRespawnListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (player == null || manager == null) {
            return;
        }

        if (manager.isSpectatorParticipant(player.getUniqueId())) {
            Location spectator = plugin.getEventArenaManager().getSpectatorSpawn();
            if (spectator != null && spectator.getWorld() != null) {
                event.setRespawnLocation(spectator);
            }
            return;
        }

        if (!manager.isActiveParticipant(player.getUniqueId())) {
            return;
        }

        if (manager.isVampire(player.getUniqueId())) {
            Location vampire = plugin.getEventArenaManager().getVampireSpawn();
            if (vampire != null && vampire.getWorld() != null) {
                event.setRespawnLocation(vampire);
            }
            return;
        }

        if (manager.isHunter(player.getUniqueId())) {
            Location hunter = plugin.getEventArenaManager().getHunterSpawn();
            if (hunter != null && hunter.getWorld() != null) {
                event.setRespawnLocation(hunter);
            }
        }
    }
}