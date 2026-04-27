package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class EventChatListener implements Listener {

    private final DraculaVampireHunt plugin;

    public EventChatListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (player == null || manager == null) {
            return;
        }

        if (!manager.isEventParticipant(player.getUniqueId())) {
            return;
        }

        String tag = "§7[Queue]";
        if (manager.isVampire(player.getUniqueId())) {
            tag = "§5[Vampire]";
        } else if (manager.isHunter(player.getUniqueId())) {
            tag = "§b[Hunter]";
        } else if (manager.isSpectatorParticipant(player.getUniqueId())) {
            tag = "§8[Spectator]";
        }

        event.setFormat(tag + " §f" + player.getName() + "§8: §7" + event.getMessage());
    }
}