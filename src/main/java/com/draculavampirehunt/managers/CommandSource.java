package com.draculavampirehunt.managers;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * Thin wrapper around a {@link CommandSender} that distinguishes between an in-game
 * player and the console, and provides unified messaging helpers used by admin commands.
 */
public final class CommandSource {

    private final CommandSender sender;

    public CommandSource(CommandSender sender) {
        if (sender == null) {
            throw new IllegalArgumentException("CommandSource sender must not be null.");
        }
        this.sender = sender;
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static CommandSource of(CommandSender sender) {
        return new CommandSource(sender);
    }

    // ── Type checks ───────────────────────────────────────────────────────────

    public boolean isPlayer() {
        return sender instanceof Player;
    }

    public boolean isConsole() {
        return sender instanceof ConsoleCommandSender;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public CommandSender getSender() {
        return sender;
    }

    /** Returns the underlying {@link Player}, or {@code null} if this is not a player source. */
    public Player getPlayer() {
        return isPlayer() ? (Player) sender : null;
    }

    public String getName() {
        return sender.getName();
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    public void sendMessage(String message) {
        if (message != null) {
            sender.sendMessage(message);
        }
    }

    public boolean hasPermission(String permission) {
        return permission != null && sender.hasPermission(permission);
    }
}
