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

public class VampireHuntAdminCommand implements CommandExecutor, TabCompleter {

    private static final DecimalFormat PCT = new DecimalFormat("0.00");

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

            // ── Help ──────────────────────────────────────────────────────────
            case "help" -> {
                sendHelp(sender, label);
            }

            // ── Event lifecycle ───────────────────────────────────────────────
            case "start" -> {
                boolean started = manager.startEventByAdmin(VampireHuntManager.CommandSource.CONSOLE);
                if (started) chat.sendPrefixed(sender, "§aVampire Hunt start sequence triggered.");
                else         chat.sendPrefixed(sender, "§cCould not start the event. Check queue size or current phase.");
            }

            case "stop" -> {
                boolean stopped = manager.stopEventGracefully();
                if (stopped) chat.sendPrefixed(sender, "§eVampire Hunt stopped.");
                else         chat.sendPrefixed(sender, "§cThere is no active or queued event to stop.");
            }

            case "forcestop" -> {
                boolean stopped = manager.forceStopEvent();
                if (stopped) chat.sendPrefixed(sender, "§cVampire Hunt force-stopped.");
                else         chat.sendPrefixed(sender, "§cThere is no active or queued event to force-stop.");
            }

            case "reload" -> {
                plugin.reloadConfig();
                plugin.getEventStatsManager().load();
                chat.sendPrefixed(sender, "§aConfiguration and player stats reloaded.");
            }

            // ── Status ────────────────────────────────────────────────────────
            case "status" -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§4§lVampire Hunt Admin §7- Status");
                sender.sendMessage("§7Phase: §f"           + manager.getPhaseName());
                sender.sendMessage("§7Queued Players: §f"  + manager.getQueuedCount());
                sender.sendMessage("§7Ready Players: §f"   + manager.getReadyCount());
                sender.sendMessage("§7Active Players: §f"  + manager.getActiveCount());
                sender.sendMessage("§7Vampires Alive: §f"  + manager.getVampireCount());
                sender.sendMessage("§7Hunters Alive: §f"   + manager.getHunterCount());
                sender.sendMessage("§8§m--------------------------------------------------");
            }

            // ── Spawn setup ───────────────────────────────────────────────────
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly a player can set spawn points.");
                    return true;
                }
                if (args.length < 2) {
                    chat.sendPrefixed(player, "§cUsage: /" + label + " setspawn <lobby|vampire|hunter|return|spectator>");
                    return true;
                }

                String spawnType = args[1].toLowerCase(Locale.ROOT);
                String configPath = switch (spawnType) {
                    case "lobby"     -> "arena.lobby";
                    case "vampire"   -> "arena.vampire";
                    case "hunter"    -> "arena.hunter";
                    case "return"    -> "arena.return";
                    case "spectator" -> "arena.spectator";
                    default -> null;
                };

                if (configPath == null) {
                    chat.sendPrefixed(player, "§cUnknown spawn type. Use: lobby, vampire, hunter, return, spectator.");
                    return true;
                }

                plugin.getEventArenaManager().setLocation(configPath, player.getLocation());
                chat.sendPrefixed(player, "§aSpawn §f" + spawnType + " §aset to your current location.");
            }

            // ── In-event role management ──────────────────────────────────────
            case "addvampire" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: /" + label + " addvampire <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                String result = manager.adminForceVampire(target);
                chat.sendPrefixed(sender, result);
            }

            case "removevampire" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: /" + label + " removevampire <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                String result = manager.adminForceHunter(target);
                chat.sendPrefixed(sender, result);
            }

            case "addplayer" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: /" + label + " addplayer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                boolean joined = manager.joinEvent(target);
                if (!joined) {
                    chat.sendPrefixed(sender, "§ePlayer §f" + target.getName() + " §ecould not be added (may already be in the event).");
                } else {
                    chat.sendPrefixed(sender, "§aForce-added §f" + target.getName() + " §ato the event queue.");
                }
            }

            case "removeplayer" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: /" + label + " removeplayer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                boolean removed = manager.leaveEvent(target, true);
                if (!removed) {
                    chat.sendPrefixed(sender, "§ePlayer §f" + target.getName() + " §ewas not in the event.");
                } else {
                    chat.sendPrefixed(sender, "§aRemoved §f" + target.getName() + " §afrom the event.");
                }
            }

            // ── Player lists ──────────────────────────────────────────────────
            case "listplayers" -> {
                sender.sendMessage("§8§m--------------------------------------------------");
                sender.sendMessage("§4§lVampire Hunt §7- Active Players");
                sender.sendMessage(manager.getAdminPlayerListMessage());
                sender.sendMessage("§8§m--------------------------------------------------");
            }

            // ── Leaderboard ───────────────────────────────────────────────────
            case "leaderboard", "lb", "top" -> {
                String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "wins";
                sendLeaderboard(sender, category);
            }

            // ── Stats for any player ──────────────────────────────────────────
            case "stats" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: /" + label + " stats <player>");
                    return true;
                }
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                sendStats(sender, target.getUniqueId(), target.getName() != null ? target.getName() : args[1]);
            }

            default -> sendHelp(sender, label);
        }

        return true;
    }

    // ── Leaderboard helper ────────────────────────────────────────────────────

    private void sendLeaderboard(CommandSender sender, String category) {
        EventStatsManager statsManager = plugin.getEventStatsManager();
        List<UUID> allPlayers = statsManager.getAllTrackedPlayers();

        if (allPlayers.isEmpty()) {
            sender.sendMessage("§8[§4VH§8] §7No player stats recorded yet.");
            return;
        }

        record Entry(String name, long value) {}
        List<Entry> entries = new ArrayList<>();

        for (UUID uuid : allPlayers) {
            EventStatsManager.PlayerEventStats stats = statsManager.getStats(uuid);
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null) name = uuid.toString().substring(0, 8);

            long value = switch (category) {
                case "kills", "vampirekills" -> stats.getVampireKills();
                case "hunterkills"           -> stats.getHunterKills();
                case "infections"            -> stats.getInfections();
                case "events", "played"      -> stats.getEventsPlayed();
                case "streak"                -> stats.getBestWinStreak();
                default                      -> stats.getWins();
            };
            entries.add(new Entry(name, value));
        }

        entries.sort((a, b) -> Long.compare(b.value(), a.value()));
        int limit = Math.min(10, entries.size());

        String catLabel = switch (category) {
            case "kills", "vampirekills" -> "Vampire Kills";
            case "hunterkills"           -> "Hunter Kills";
            case "infections"            -> "Infections";
            case "events", "played"      -> "Events Played";
            case "streak"                -> "Best Win Streak";
            default                      -> "Wins";
        };

        sender.sendMessage("§8§m--------------------------------------------------");
        sender.sendMessage("§4§lVampire Hunt §7- Top " + limit + " by §f" + catLabel);
        for (int i = 0; i < limit; i++) {
            Entry e = entries.get(i);
            String medal = switch (i) {
                case 0 -> "§6#1 ";
                case 1 -> "§7#2 ";
                case 2 -> "§c#3 ";
                default -> "§8#" + (i + 1) + " ";
            };
            sender.sendMessage(medal + "§f" + e.name() + " §8- §e" + e.value());
        }
        sender.sendMessage("§7Categories: wins | kills | hunterkills | infections | events | streak");
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    // ── Stats helper ─────────────────────────────────────────────────────────

    private void sendStats(CommandSender sender, UUID targetId, String targetName) {
        EventStatsManager.PlayerEventStats stats = plugin.getEventStatsManager().getStats(targetId);
        double winRate = plugin.getEventStatsManager().getWinRate(targetId) * 100.0;
        String primaryTitle = plugin.getEventStatsManager().getPrimaryTitle(targetId);

        sender.sendMessage("§8§m--------------------------------------------------");
        sender.sendMessage("§4§lVampire Hunt §7- Stats: §f" + targetName);
        if (!primaryTitle.isBlank()) sender.sendMessage("§7Title: §d" + primaryTitle);
        sender.sendMessage("§7Events Played: §f" + stats.getEventsPlayed());
        sender.sendMessage("§7Wins: §a" + stats.getWins() + " §7| Losses: §c" + stats.getLosses() + " §7| Win Rate: §e" + PCT.format(winRate) + "%");
        sender.sendMessage("§7Vampire Wins: §5" + stats.getVampireWins() + " §7| Hunter Wins: §b" + stats.getHunterWins());
        sender.sendMessage("§7Vampire Kills: §5" + stats.getVampireKills() + " §7| Hunter Kills: §b" + stats.getHunterKills() + " §7| Infections: §4" + stats.getInfections());
        sender.sendMessage("§7Current Streak: §e" + stats.getCurrentWinStreak() + " §7| Best Streak: §6" + stats.getBestWinStreak());
        List<String> titles = plugin.getEventStatsManager().getUnlockedTitles(targetId);
        sender.sendMessage("§7Titles: §f" + (titles.isEmpty() ? "None" : String.join("§7, §f", titles)));
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m--------------------------------------------------");
        sender.sendMessage("§4§lVampire Hunt Admin Commands");
        sender.sendMessage("§c/" + label + " help                     §8- §7Show this page.");
        sender.sendMessage("§c/" + label + " start                    §8- §7Force-start the countdown.");
        sender.sendMessage("§c/" + label + " stop                     §8- §7Stop the event gracefully.");
        sender.sendMessage("§c/" + label + " forcestop                §8- §7Force-stop immediately.");
        sender.sendMessage("§c/" + label + " reload                   §8- §7Reload config and stats.");
        sender.sendMessage("§c/" + label + " status                   §8- §7Show live event status.");
        sender.sendMessage("§c/" + label + " setspawn <type>          §8- §7Set spawn at your location.");
        sender.sendMessage("§7  Types: lobby, vampire, hunter, return, spectator");
        sender.sendMessage("§c/" + label + " addvampire <player>      §8- §7Force a player to the vampire team.");
        sender.sendMessage("§c/" + label + " removevampire <player>   §8- §7Switch a vampire back to hunter.");
        sender.sendMessage("§c/" + label + " addplayer <player>       §8- §7Force-add a player to the queue.");
        sender.sendMessage("§c/" + label + " removeplayer <player>    §8- §7Force-remove a player.");
        sender.sendMessage("§c/" + label + " listplayers              §8- §7List all active participants.");
        sender.sendMessage("§c/" + label + " leaderboard [category]   §8- §7Show top-10 leaderboard.");
        sender.sendMessage("§7  Categories: wins | kills | hunterkills | infections | events | streak");
        sender.sendMessage("§c/" + label + " stats <player>           §8- §7View any player's stats.");
        sender.sendMessage("§8§m--------------------------------------------------");
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("draculavampirehunt.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return filter(args[0],
                    "help", "start", "stop", "forcestop", "reload", "status",
                    "setspawn", "addvampire", "removevampire", "addplayer", "removeplayer",
                    "listplayers", "leaderboard", "stats");
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "setspawn"                         -> filter(args[1], "lobby", "vampire", "hunter", "return", "spectator");
                case "leaderboard", "lb", "top"         -> filter(args[1], "wins", "kills", "hunterkills", "infections", "events", "streak");
                case "addvampire","removevampire",
                     "addplayer","removeplayer","stats"  -> onlinePlayers(args[1]);
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> matches = new ArrayList<>();
        String lp = prefix.toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(lp)) matches.add(p.getName());
        }
        return matches;
    }

    private List<String> filter(String input, String... values) {
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(value);
        }
        return matches;
    }
}
