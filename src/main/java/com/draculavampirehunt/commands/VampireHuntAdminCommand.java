package com.draculavampirehunt.commands;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.ChatManager;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VampireHuntAdminCommand implements CommandExecutor, TabCompleter {

    private final DraculaVampireHunt plugin;

    public VampireHuntAdminCommand(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ChatManager chat = plugin.getChatManager();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!sender.hasPermission("draculavampirehunt.admin")) {
            chat.sendPrefixed(sender, "§cYou do not have permission to use admin commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "help" -> {
                sendHelp(sender, label);
                return true;
            }

            case "start" -> {
                boolean started = manager.startEventByAdmin(VampireHuntManager.CommandSource.CONSOLE);
                if (started) {
                    chat.sendPrefixed(sender, "§aVampire Hunt start sequence triggered.");
                } else {
                    chat.sendPrefixed(sender, "§cCould not start the event. Check queue size or current phase.");
                }
                return true;
            }

            case "stop" -> {
                boolean stopped = manager.stopEventGracefully();
                if (stopped) {
                    chat.sendPrefixed(sender, "§eVampire Hunt stopped.");
                } else {
                    chat.sendPrefixed(sender, "§cThere is no active or queued event to stop.");
                }
                return true;
            }

            case "forcestop" -> {
                boolean stopped = manager.forceStopEvent();
                if (stopped) {
                    chat.sendPrefixed(sender, "§cVampire Hunt force-stopped.");
                } else {
                    chat.sendPrefixed(sender, "§cThere is no active or queued event to force-stop.");
                }
                return true;
            }

            case "reload" -> {
                plugin.reloadConfig();
                plugin.getEventStatsManager().load();
                chat.sendPrefixed(sender, "§aConfiguration and player stats reloaded.");
                return true;
            }

            case "status" -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§4§lVampire Hunt Admin §7- Status");
                sender.sendMessage("§7Phase: §f" + manager.getPhaseName());
                sender.sendMessage("§7Queued Players: §f" + manager.getQueuedCount());
                sender.sendMessage("§7Ready Players: §f" + manager.getReadyCount());
                sender.sendMessage("§7Active Players: §f" + manager.getActiveCount());
                sender.sendMessage("§7Vampires Alive: §f" + manager.getVampireCount());
                sender.sendMessage("§7Hunters Alive: §f" + manager.getHunterCount());
                sender.sendMessage("§8§m--------------------------------------------------");
                return true;
            }

            default -> {
                sendHelp(sender, label);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m--------------------------------------------------");
        sender.sendMessage("§4§lVampire Hunt Admin Commands");
        sender.sendMessage("§c/" + label + " help §8- §7Show this help page.");
        sender.sendMessage("§c/" + label + " start §8- §7Force-start the event countdown.");
        sender.sendMessage("§c/" + label + " stop §8- §7Stop the event gracefully.");
        sender.sendMessage("§c/" + label + " forcestop §8- §7Force-stop the event immediately.");
        sender.sendMessage("§c/" + label + " reload §8- §7Reload config and player stats.");
        sender.sendMessage("§c/" + label + " status §8- §7Show live event status.");
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("draculavampirehunt.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(args[0], "help", "start", "stop", "forcestop", "reload", "status");
        }

        return Collections.emptyList();
    }

    private List<String> filter(String input, String... values) {
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }

        return matches;
    }
}