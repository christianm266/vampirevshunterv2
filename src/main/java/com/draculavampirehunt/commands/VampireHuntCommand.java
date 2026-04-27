package com.draculavampirehunt.commands;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.ChatManager;
import com.draculavampirehunt.managers.EventStatsManager;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class VampireHuntCommand implements CommandExecutor, TabCompleter {

    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("0.00");

    private final DraculaVampireHunt plugin;

    public VampireHuntCommand(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ChatManager chat = plugin.getChatManager();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!sender.hasPermission("draculavampirehunt.use")) {
            chat.sendPrefixed(sender, "§cYou do not have permission to use this command.");
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

            case "join" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can join the event.");
                    return true;
                }

                manager.joinEvent(player);
                return true;
            }

            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can leave the event.");
                    return true;
                }

                boolean success = manager.leaveEvent(player, true);
                if (!success) {
                    chat.sendPrefixed(player, "§eYou are not in the event.");
                }
                return true;
            }

            case "ready" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can ready up.");
                    return true;
                }

                if (!manager.setPlayerReady(player)) {
                    chat.sendPrefixed(player, "§eCould not mark you ready.");
                }
                return true;
            }

            case "unready" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can unready.");
                    return true;
                }

                if (!manager.unreadyPlayer(player)) {
                    chat.sendPrefixed(player, "§eCould not remove your ready state.");
                }
                return true;
            }

            case "class" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can select a class.");
                    return true;
                }

                if (args.length < 2) {
                    chat.sendPrefixed(player, "§cUsage: /" + label + " class <stalker|brute|tracker|priest>");
                    return true;
                }

                manager.selectClass(player, args[1]);
                return true;
            }

            case "ability" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can use a class ability.");
                    return true;
                }

                if (!manager.useClassAbility(player)) {
                    chat.sendPrefixed(player, "§eCould not use your class ability right now.");
                }
                return true;
            }

            case "teams" -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§4§lVampire Hunt §7- Live Teams");
                sender.sendMessage(manager.getTeamCountLine());
                sender.sendMessage("§8§m--------------------------------------------------");
                return true;
            }

            case "specteleport" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can spectate teleport.");
                    return true;
                }

                if (!manager.isSpectatorParticipant(player.getUniqueId())) {
                    chat.sendPrefixed(player, "§cYou must be spectating to use this.");
                    return true;
                }

                String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "next";

                Player target = switch (mode) {
                    case "hunters", "hunter" -> manager.getNextSpectatorTarget(player, false, true);
                    case "vampires", "vampire" -> manager.getNextSpectatorTarget(player, true, false);
                    default -> manager.getNextSpectatorTarget(player, false, false);
                };

                if (target == null) {
                    chat.sendPrefixed(player, "§eNo valid spectator target found.");
                    return true;
                }

                player.setSpectatorTarget(target);
                chat.sendPrefixed(player, "§aNow spectating §f" + target.getName() + "§a.");
                return true;
            }

            case "status", "phase" -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§4§lVampire Hunt §7- Status");
                sender.sendMessage("§7Phase: §f" + manager.getPhaseName());
                sender.sendMessage("§7Queued Players: §f" + manager.getQueuedCount());
                sender.sendMessage("§7Ready Players: §f" + manager.getReadyCount());
                sender.sendMessage("§7Active Players: §f" + manager.getActiveCount());
                sender.sendMessage("§7Vampires Alive: §f" + manager.getVampireCount());
                sender.sendMessage("§7Hunters Alive: §f" + manager.getHunterCount());
                sender.sendMessage("§8§m--------------------------------------------------");
                return true;
            }

            case "stats" -> {
                if (args.length >= 2 && sender.hasPermission("draculavampirehunt.admin.stats")) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    sendStats(sender, target.getUniqueId(), target.getName());
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cConsole must specify a player: /" + label + " stats <player>");
                    return true;
                }

                sendStats(sender, player.getUniqueId(), player.getName());
                return true;
            }

            case "info" -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§4§lVampire Hunt §7- Event Info");
                sender.sendMessage("§7Hunters win by killing all vampires.");
                sender.sendMessage("§7Vampires win by converting all hunters.");
                sender.sendMessage("§7Ready-check happens before countdown if enabled.");
                sender.sendMessage("§7Classes: Stalker, Brute, Tracker, Priest.");
                sender.sendMessage("§7Spectators can cycle players and view team counts.");
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
        sender.sendMessage("§4§lVampire Hunt §7- Player Commands");
        sender.sendMessage("§c/" + label + " help §8- §7Show this help page.");
        sender.sendMessage("§c/" + label + " join §8- §7Join the event queue.");
        sender.sendMessage("§c/" + label + " leave §8- §7Leave the queue, event, or spectator mode.");
        sender.sendMessage("§c/" + label + " ready §8- §7Mark yourself ready.");
        sender.sendMessage("§c/" + label + " unready §8- §7Remove your ready state.");
        sender.sendMessage("§c/" + label + " class <name> §8- §7Choose your event class.");
        sender.sendMessage("§c/" + label + " ability §8- §7Use your class ability.");
        sender.sendMessage("§c/" + label + " teams §8- §7View live team counts.");
        sender.sendMessage("§c/" + label + " specteleport <next|hunters|vampires> §8- §7Cycle spectator target.");
        sender.sendMessage("§c/" + label + " status §8- §7View the current event phase.");
        sender.sendMessage("§c/" + label + " stats §8- §7View your event stats.");
        sender.sendMessage("§c/" + label + " info §8- §7View how the event works.");
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    private void sendStats(CommandSender sender, UUID targetId, String targetName) {
        EventStatsManager.PlayerEventStats stats = plugin.getEventStatsManager().getStats(targetId);
        double winRate = plugin.getEventStatsManager().getWinRate(targetId) * 100.0D;
        List<String> titles = plugin.getEventStatsManager().getUnlockedTitles(targetId);

        sender.sendMessage("§8§m--------------------------------------------------");
        sender.sendMessage("§4§lVampire Hunt §7- Stats for §f" + targetName);
        sender.sendMessage("§7Wins: §f" + stats.getWins());
        sender.sendMessage("§7Losses: §f" + stats.getLosses());
        sender.sendMessage("§7Events Played: §f" + stats.getEventsPlayed());
        sender.sendMessage("§7Win Rate: §f" + PERCENT_FORMAT.format(winRate) + "%");
        sender.sendMessage("§7Vampire Kills: §f" + stats.getVampireKills());
        sender.sendMessage("§7Hunter Kills: §f" + stats.getHunterKills());
        sender.sendMessage("§7Infections: §f" + stats.getInfections());
        sender.sendMessage("§7Vampire Wins: §f" + stats.getVampireWins());
        sender.sendMessage("§7Hunter Wins: §f" + stats.getHunterWins());
        sender.sendMessage("§7Best Win Streak: §f" + stats.getBestWinStreak());
        sender.sendMessage("§7Current Win Streak: §f" + stats.getCurrentWinStreak());
        sender.sendMessage("§7Unlocked Titles: §f" + (titles.isEmpty() ? "None" : String.join("§7, §f", titles)));
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("draculavampirehunt.use")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(args[0],
                    "help",
                    "join",
                    "leave",
                    "ready",
                    "unready",
                    "class",
                    "ability",
                    "teams",
                    "specteleport",
                    "status",
                    "phase",
                    "stats",
                    "info"
            );
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("class")) {
            return filter(args[1], "stalker", "brute", "tracker", "priest");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("specteleport")) {
            return filter(args[1], "next", "hunters", "vampires");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stats") && sender.hasPermission("draculavampirehunt.admin.stats")) {
            List<String> matches = new ArrayList<>();
            String input = args[1].toLowerCase(Locale.ROOT);

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                    matches.add(online.getName());
                }
            }

            return matches;
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