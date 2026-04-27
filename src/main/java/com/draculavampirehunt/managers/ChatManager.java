package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class ChatManager {

    private static final String DEFAULT_PREFIX = "§8[§4Vampire Hunt§8] §7";

    private final DraculaVampireHunt plugin;

    public ChatManager(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    public String getPrefix() {
        String raw = plugin.getConfig().getString("messages.prefix", DEFAULT_PREFIX);
        return color(raw);
    }

    public String color(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public void sendPrefixed(CommandSender sender, String message) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(getPrefix() + color(message));
    }

    public void sendPrefixed(Player player, String message) {
        if (player == null) {
            return;
        }
        player.sendMessage(getPrefix() + color(message));
    }

    public void broadcastGlobal(String message) {
        Bukkit.broadcastMessage(getPrefix() + color(message));
    }

    public void broadcastToParticipants(String message) {
        VampireHuntManager manager = plugin.getVampireHuntManager();
        if (manager == null) {
            broadcastGlobal(message);
            return;
        }

        String finalMessage = getPrefix() + color(message);
        for (UUID uuid : manager.getAllParticipantIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(finalMessage);
            }
        }
    }

    public void messageParticipant(UUID playerId, String message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            sendPrefixed(player, message);
        }
    }
}