package com.draculavampirehunt.commands;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.ChatManager;
import com.draculavampirehunt.managers.EventStatsManager;
import com.draculavampirehunt.managers.VampireHuntManager;
import com.draculavampirehunt.managers.VoteManager;
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
                sender.sendMessage("§7Spectators vote on the next round modifier after each round.");
                sender.sendMessage("§8§m--------------------------------------------------");
                return true;
            }

            case "vote" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can vote.");
                    return true;
                }

                VoteManager voteManager = plugin.getVampireHuntManager().getVoteManager();
                if (!voteManager.isVoteOpen()) {
                    chat.sendPrefixed(player, "§eThere is no active vote right now.");
                    return true;
                }

                if (args.length < 2) {
                    chat.sendPrefixed(player, "§7Open options: §f" + voteManager.getOptionsDisplay());
                    chat.sendPrefixed(player, "§cUsage: /" + label + " vote <double|nocompass|sd|fog|none>");
                    return true;
                }

                boolean cast = voteManager.castVote(player.getUniqueId(), args[1]);
                if (cast) {
                    chat.sendPrefixed(player, "§aVote cast! Current tally: §f" + voteManager.getTallyDisplay());
                } else {
                    chat.sendPrefixed(player, "§cInvalid vote option. Choose: §f" + voteManager.getOptionsDisplay());
                }
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
        sender.sendMessage("§4§lVampire Hunt §8» §7Player Commands");
        sender.sendMessage("");
        sender.sendMessage("§6§lJoining & Queue");
        sender.sendMessage("  §c/" + label + " join       §8- §7Enter the event queue.");
        sender.sendMessage("  §c/" + label + " leave      §8- §7Leave queue, event, or spectator mode.");
        sender.sendMessage("  §c/" + label + " ready      §8- §7Mark yourself ready before start.");
        sender.sendMessage("  §c/" + label + " unready    §8- §7Remove your ready state.");
        sender.sendMessage("");
        sender.sendMessage("§6§lIn-Event Actions");
        sender.sendMessage("  §c/" + label + " class <stalker|brute|tracker|priest>   §8- §7Select your class.");
        sender.sendMessage("  §c/" + label + " ability    §8- §7Activate your class ability.");
        sender.sendMessage("  §c/" + label + " teams      §8- §7View live hunter/vampire counts.");
        sender.sendMessage("  §c/" + label + " status     §8- §7View the current event phase & counts.");
        sender.sendMessage("");
        sender.sendMessage("§6§lSpectator");
        sender.sendMessage("  §c/" + label + " specteleport <next|hunters|vampires>   §8- §7Cycle spectator target.");
        sender.sendMessage("  §c/" + label + " vote <double|nocompass|sd|fog|none>    §8- §7Vote for next round modifier.");
        sender.sendMessage("");
        sender.sendMessage("§6§lInfo & Stats");
        sender.sendMessage("  §c/" + label + " stats [player]  §8- §7View your (or another player's) stats.");
        sender.sendMessage("  §c/" + label + " info            §8- §7How the event works (rules & win conditions).");
        sender.sendMessage("  §c/" + label + " help            §8- §7Show this help page.");
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    private void sendStats(CommandSender sender, UUID targetId, String targetName) {
        EventStatsManager.PlayerEventStats stats = plugin.getEventStatsManager().getStats(targetId);
        double winRate = plugin.getEventStatsManager().getWinRate(targetId) * 100.0D;
        double killDeathRatio = plugin.getEventStatsManager().getKillDeathRatio(targetId);

        sender.sendMessage("§8§m--------------------------------------------------");
        sender.sendMessage("§4§lVampire Hunt §7- Stats for §f" + (targetName != null ? targetName : targetId.toString()));
        sender.sendMessage("§7Events Played: §f" + stats.eventsPlayed);
        sender.sendMessage("§7Wins: §f" + stats.wins + "  §7Losses: §f" + stats.losses);
        sender.sendMessage("§7Win Rate: §f" + PERCENT_FORMAT.format(winRate) + "%");
        sender.sendMessage("§7Total Kills: §f" + stats.totalKills + "  §7Total Deaths: §f" + stats.totalDeaths);
        sender.sendMessage("§7K/D Ratio: §f" + PERCENT_FORMAT.format(killDeathRatio));
        sender.sendMessage("§7Infections: §f" + stats.infections);
        sender.sendMessage("§7Vampire Rounds: §f" + stats.vampireRounds + "  §7Hunter Rounds: §f" + stats.hunterRounds);
        if (!stats.unlockedTitles.isEmpty()) {
            sender.sendMessage("§7Titles: §f" + String.join("§7, §f", stats.unlockedTitles));
        }
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("draculavampirehunt.use")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "help", "join", "leave", "ready", "unready",
                    "class", "ability", "teams", "specteleport",
                    "status", "stats", "info", "vote"
            ));
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "class" -> List.of("stalker", "brute", "tracker", "priest").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
                case "specteleport" -> List.of("next", "hunters", "vampires").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
                case "vote" -> List.of("double", "nocompass", "sd", "fog", "none").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }
}
