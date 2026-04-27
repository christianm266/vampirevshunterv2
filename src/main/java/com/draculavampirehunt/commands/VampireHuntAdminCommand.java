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

    private static final String LINE  = "\u00a78\u00a7m----------------------------------------------------";
    private static final String ARROW = "  \u00a78\u00bb ";
    private static final DecimalFormat PCT = new DecimalFormat("0.00");

    private final DraculaVampireHunt plugin;

    public VampireHuntAdminCommand(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ChatManager chat = plugin.getChatManager();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!sender.hasPermission("draculavampirehunt.admin")) {
            chat.sendPrefixed(sender, "\u00a7cYou do not have permission to use admin commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {

            // ── Help ──────────────────────────────────────────────────────────
            case "help" -> sendHelp(sender, label);

            // ── Event lifecycle ───────────────────────────────────────────────
            case "start" -> {
                if (manager.startEventByAdmin(VampireHuntManager.CommandSource.CONSOLE)) {
                    chat.sendPrefixed(sender, "\u00a7aVampire Hunt countdown triggered.");
                } else {
                    chat.sendPrefixed(sender, "\u00a7cCannot start — queue size min 2, current phase: \u00a7f" + manager.getPhaseName() + "\u00a7c.");
                }
            }

            case "stop" -> {
                if (manager.stopEventGracefully()) {
                    chat.sendPrefixed(sender, "\u00a7eVampire Hunt stopped gracefully.");
                } else {
                    chat.sendPrefixed(sender, "\u00a7cNo active or queued event to stop.");
                }
            }

            case "forcestop" -> {
                if (manager.forceStopEvent()) {
                    chat.sendPrefixed(sender, "\u00a7cVampire Hunt force-stopped.");
                } else {
                    chat.sendPrefixed(sender, "\u00a7cNo active or queued event to force-stop.");
                }
            }

            case "reload" -> {
                plugin.reloadConfig();
                plugin.getEventStatsManager().load();
                chat.sendPrefixed(sender, "\u00a7aConfiguration and player stats reloaded.");
            }

            // ── Status ────────────────────────────────────────────────────────
            case "status" -> sendStatus(sender, manager);

            // ── Spawn setup ───────────────────────────────────────────────────
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly a player can set spawn points.");
                    return true;
                }
                if (args.length < 2) {
                    sendSpawnHelp(player, label);
                    return true;
                }
                String spawnType = args[1].toLowerCase(Locale.ROOT);
                String configPath = switch (spawnType) {
                    case "lobby"     -> "arena.lobby";
                    case "vampire"   -> "arena.vampire";
                    case "hunter"    -> "arena.hunter";
                    case "return"    -> "arena.return";
                    case "spectator" -> "arena.spectator";
                    default          -> null;
                };
                if (configPath == null) {
                    chat.sendPrefixed(player, "\u00a7cUnknown spawn type. Valid: lobby, vampire, hunter, return, spectator.");
                    return true;
                }
                plugin.getEventArenaManager().setLocation(configPath, player.getLocation());
                chat.sendPrefixed(player, "\u00a7aSpawn \u00a7f" + spawnType + " \u00a7aset to your current location.");
            }

            // ── In-event role management ──────────────────────────────────────
            case "addvampire" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "\u00a7cUsage: \u00a7f/" + label + " addvampire <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "\u00a7cPlayer \u00a7f" + args[1] + " \u00a7cis not online.");
                    return true;
                }
                chat.sendPrefixed(sender, manager.adminForceVampire(target));
            }

            case "removevampire" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "\u00a7cUsage: \u00a7f/" + label + " removevampire <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "\u00a7cPlayer \u00a7f" + args[1] + " \u00a7cis not online.");
                    return true;
                }
                chat.sendPrefixed(sender, manager.adminForceHunter(target));
            }

            case "addplayer" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "\u00a7cUsage: \u00a7f/" + label + " addplayer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "\u00a7cPlayer \u00a7f" + args[1] + " \u00a7cis not online.");
                    return true;
                }
                if (manager.joinEvent(target)) {
                    chat.sendPrefixed(sender, "\u00a7aForce-added \u00a7f" + target.getName() + " \u00a7ato the event queue.");
                } else {
                    chat.sendPrefixed(sender, "\u00a7eCould not add \u00a7f" + target.getName() + " \u00a7e(may already be in the event).");
                }
            }

            case "removeplayer" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "\u00a7cUsage: \u00a7f/" + label + " removeplayer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "\u00a7cPlayer \u00a7f" + args[1] + " \u00a7cis not online.");
                    return true;
                }
                if (manager.leaveEvent(target, true)) {
                    chat.sendPrefixed(sender, "\u00a7aRemoved \u00a7f" + target.getName() + " \u00a7afrom the event.");
                } else {
                    chat.sendPrefixed(sender, "\u00a7ePlayer \u00a7f" + target.getName() + " \u00a7ewas not in the event.");
                }
            }

            // ── Player lists ──────────────────────────────────────────────────
            case "listplayers" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("  \u00a74\u00a7lVampire Hunt Admin \u00a78\u2014 \u00a77Active Players");
                sender.sendMessage(manager.getAdminPlayerListMessage());
                sender.sendMessage(LINE);
            }

            // ── Leaderboard ───────────────────────────────────────────────────
            case "leaderboard", "lb", "top" -> {
                String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "wins";
                sendLeaderboard(sender, category, label);
            }

            // ── Stats for any player ──────────────────────────────────────────
            case "stats" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "\u00a7cUsage: \u00a7f/" + label + " stats <player>");
                    return true;
                }
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                sendStats(sender, target.getUniqueId(), target.getName() != null ? target.getName() : args[1]);
            }

            default -> {
                String suggestion = findClosestSub(sub);
                if (suggestion != null) {
                    chat.sendPrefixed(sender, "\u00a7cUnknown subcommand \u00a7f'" + args[0] + "'\u00a7c. Did you mean \u00a7f/" + label + " " + suggestion + "\u00a7c?");
                } else {
                    chat.sendPrefixed(sender, "\u00a7cUnknown subcommand \u00a7f'" + args[0] + "'\u00a7c. Type \u00a7f/" + label + " help\u00a7c for a full list.");
                }
            }
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Closest subcommand suggestion
    // ─────────────────────────────────────────────────────────────────────────

    private static final List<String> ALL_ADMIN_SUBS = List.of(
            "help", "start", "stop", "forcestop", "reload", "status",
            "setspawn", "addvampire", "removevampire", "addplayer", "removeplayer",
            "listplayers", "leaderboard", "stats"
    );

    private String findClosestSub(String input) {
        if (input == null || input.isBlank()) return null;
        String lower = input.toLowerCase(Locale.ROOT);
        for (String s : ALL_ADMIN_SUBS) {
            if (s.startsWith(lower)) return s;
        }
        for (String s : ALL_ADMIN_SUBS) {
            if (s.contains(lower)) return s;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Help — full sectioned admin reference
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String label) {
        VampireHuntManager manager = plugin.getVampireHuntManager();
        String phase = manager.getPhaseName();

        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt Admin \u00a78\u2014 \u00a77Command Reference  \u00a78[\u00a7f/" + label + "\u00a78]");
        sender.sendMessage("  \u00a77Current phase: \u00a7f" + phase
                + "  \u00a77Queue: \u00a7f" + manager.getQueuedCount()
                + "  \u00a75V: \u00a7f" + manager.getVampireCount()
                + "  \u00a7bH: \u00a7f" + manager.getHunterCount());
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lEvent Lifecycle");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " start         \u00a78\u2502 \u00a77Force-start countdown (bypasses ready check)");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " stop          \u00a78\u2502 \u00a77Stop gracefully (restores all players)");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " forcestop     \u00a78\u2502 \u00a77Immediately stop and restore (no winner)");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " reload        \u00a78\u2502 \u00a77Reload config.yml and player stats from disk");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " status        \u00a78\u2502 \u00a77Detailed live phase, queue, and team counts");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lArena Setup");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " setspawn <type>   \u00a78\u2502 \u00a77Stand at location and set a spawn point");
        sender.sendMessage("    \u00a78Types: \u00a7flobby \u00a78\u00b7 \u00a7fvampire \u00a78\u00b7 \u00a7fhunter \u00a78\u00b7 \u00a7freturn \u00a78\u00b7 \u00a7fspectator");
        sender.sendMessage("    \u00a78Tip: run \u00a7f/" + label + " setspawn\u00a78 with no argument for a guided list.");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lPlayer Management");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " addplayer <p>      \u00a78\u2502 \u00a77Force-add a player to the queue");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " removeplayer <p>   \u00a78\u2502 \u00a77Force-remove a player from the event");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " addvampire <p>     \u00a78\u2502 \u00a77Switch a player to the vampire team");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " removevampire <p>  \u00a78\u2502 \u00a77Switch a vampire back to hunter team");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " listplayers        \u00a78\u2502 \u00a77List all current participants by role");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lStats & Leaderboards");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " stats <player>          \u00a78\u2502 \u00a77Full stats for any player (including offline)");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " leaderboard [category]  \u00a78\u2502 \u00a77Top-10 leaderboard");
        sender.sendMessage("    \u00a78Categories: \u00a7fwins \u00a78\u00b7 \u00a7fkills \u00a78\u00b7 \u00a7fhunterkills \u00a78\u00b7 \u00a7finfections \u00a78\u00b7 \u00a7fevents \u00a78\u00b7 \u00a7fstreak");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lVoting (Round Modifiers)");
        sender.sendMessage("  \u00a77Spectators vote on next-round modifiers via \u00a7f/vhunt vote\u00a77.");
        sender.sendMessage("  \u00a77Vote opens automatically when a round ends (non-forced).");
        sender.sendMessage("  \u00a78Modifiers: \u00a7fdouble \u00a78\u00b7 \u00a7fnocompass \u00a78\u00b7 \u00a7fsd \u00a78\u00b7 \u00a7ffog \u00a78\u00b7 \u00a7fnone");
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status
    // ─────────────────────────────────────────────────────────────────────────

    private void sendStatus(CommandSender sender, VampireHuntManager manager) {
        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt Admin \u00a78\u2014 \u00a77Live Status");
        sender.sendMessage("");
        sender.sendMessage("  \u00a77Phase:          \u00a7f" + manager.getPhaseName());
        sender.sendMessage("  \u00a77Queued Players: \u00a7f" + manager.getQueuedCount()
                + "  \u00a77Ready: \u00a7f" + manager.getReadyCount());
        sender.sendMessage("  \u00a77Active Players: \u00a7f" + manager.getActiveCount());
        sender.sendMessage("  \u00a77Vampires Alive: \u00a75" + manager.getVampireCount()
                + "  \u00a77Hunters Alive: \u00a7b" + manager.getHunterCount());
        sender.sendMessage("  \u00a77Spectators:     \u00a7f" + manager.getSpectatorIds().size());
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spawn help — shown when /vhadmin setspawn used with no argument
    // ─────────────────────────────────────────────────────────────────────────

    private void sendSpawnHelp(Player player, String label) {
        player.sendMessage(LINE);
        player.sendMessage("  \u00a74\u00a7lVampire Hunt Admin \u00a78\u2014 \u00a77Spawn Setup");
        player.sendMessage("");
        player.sendMessage("  \u00a77Stand at the location you want to set, then run:");
        player.sendMessage("");
        player.sendMessage(ARROW + "\u00a7f/" + label + " setspawn lobby     \u00a78\u2502 \u00a77Queue / waiting area");
        player.sendMessage(ARROW + "\u00a7f/" + label + " setspawn vampire   \u00a78\u2502 \u00a75Vampire \u00a77team spawn");
        player.sendMessage(ARROW + "\u00a7f/" + label + " setspawn hunter    \u00a78\u2502 \u00a7bHunter \u00a77team spawn");
        player.sendMessage(ARROW + "\u00a7f/" + label + " setspawn return    \u00a78\u2502 \u00a77Post-event return point");
        player.sendMessage(ARROW + "\u00a7f/" + label + " setspawn spectator \u00a78\u2502 \u00a77Spectator camera perch above arena");
        player.sendMessage("");
        player.sendMessage("  \u00a77All 5 spawn types must be set before \u00a7f/" + label + " start\u00a77 will work.");
        player.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leaderboard
    // ─────────────────────────────────────────────────────────────────────────

    private void sendLeaderboard(CommandSender sender, String category, String label) {
        EventStatsManager statsManager = plugin.getEventStatsManager();
        List<UUID> allPlayers = statsManager.getAllTrackedPlayers();

        if (allPlayers.isEmpty()) {
            sender.sendMessage("\u00a78[\u00a74VH\u00a78] \u00a77No player stats recorded yet.");
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

        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt \u00a78\u2014 \u00a77Top " + limit + " by \u00a7f" + catLabel);
        sender.sendMessage("");
        for (int i = 0; i < limit; i++) {
            Entry e = entries.get(i);
            String medal = switch (i) {
                case 0  -> "\u00a76\u00a7l#1 ";
                case 1  -> "\u00a77\u00a7l#2 ";
                case 2  -> "\u00a7c\u00a7l#3 ";
                default -> "\u00a78#" + (i + 1) + " ";
            };
            sender.sendMessage("  " + medal + "\u00a7f" + e.name() + " \u00a78\u2014 \u00a7e" + e.value());
        }
        sender.sendMessage("");
        sender.sendMessage("  \u00a78Categories: \u00a7f/" + label + " leaderboard \u00a78[wins|kills|hunterkills|infections|events|streak]");
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    private void sendStats(CommandSender sender, UUID targetId, String targetName) {
        EventStatsManager statsManager = plugin.getEventStatsManager();
        EventStatsManager.PlayerEventStats stats = statsManager.getStats(targetId);
        double winRate = statsManager.getWinRate(targetId) * 100.0;
        String primaryTitle = statsManager.getPrimaryTitle(targetId);

        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt Admin \u00a78\u2014 \u00a77Stats: \u00a7f" + targetName);
        sender.sendMessage("");
        if (primaryTitle != null && !primaryTitle.isBlank()) {
            sender.sendMessage("  \u00a77Title:          \u00a7d" + primaryTitle);
        }
        sender.sendMessage("  \u00a77Events Played:  \u00a7f" + stats.getEventsPlayed());
        sender.sendMessage("  \u00a7aWins: \u00a7f" + stats.getWins()
                + "  \u00a7cLosses: \u00a7f" + stats.getLosses()
                + "  \u00a7eWin Rate: \u00a7f" + PCT.format(winRate) + "%");
        sender.sendMessage("  \u00a75Vampire Wins: \u00a7f" + stats.getVampireWins()
                + "   \u00a7bHunter Wins: \u00a7f" + stats.getHunterWins());
        sender.sendMessage("  \u00a75Vampire Kills: \u00a7f" + stats.getVampireKills()
                + "   \u00a7bHunter Kills: \u00a7f" + stats.getHunterKills()
                + "   \u00a74Infections: \u00a7f" + stats.getInfections());
        sender.sendMessage("  \u00a7eCurrent Streak: \u00a7f" + stats.getCurrentWinStreak()
                + "  \u00a76Best Streak: \u00a7f" + stats.getBestWinStreak());
        List<String> titles = statsManager.getUnlockedTitles(targetId);
        sender.sendMessage("  \u00a77Titles: \u00a7d" + (titles.isEmpty() ? "\u00a78None" : String.join("\u00a77, \u00a7d", titles)));
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab completion
    // ─────────────────────────────────────────────────────────────────────────

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
                case "setspawn"                        -> filter(args[1], "lobby", "vampire", "hunter", "return", "spectator");
                case "leaderboard", "lb", "top"        -> filter(args[1], "wins", "kills", "hunterkills", "infections", "events", "streak");
                case "addvampire", "removevampire",
                     "addplayer", "removeplayer",
                     "stats"                           -> onlinePlayers(args[1]);
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
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(v);
        }
        return out;
    }
}
