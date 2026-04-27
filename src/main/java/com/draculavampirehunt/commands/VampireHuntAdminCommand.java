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

    private static final String LINE  = "§8§m----------------------------------------------------";
    private static final String ARROW = "  §8» ";
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
            case "help" -> sendHelp(sender, label);

            // ── Event lifecycle ───────────────────────────────────────────────
            case "start" -> {
                if (manager.startEventByAdmin(VampireHuntManager.CommandSource.CONSOLE)) {
                    chat.sendPrefixed(sender, "§aVampire Hunt countdown triggered.");
                } else {
                    chat.sendPrefixed(sender, "§cCannot start: check queue size (min 2) or current phase (§f" + manager.getPhaseName() + "§c).");
                }
            }

            case "stop" -> {
                if (manager.stopEventGracefully()) {
                    chat.sendPrefixed(sender, "§eVampire Hunt stopped gracefully.");
                } else {
                    chat.sendPrefixed(sender, "§cNo active or queued event to stop.");
                }
            }

            case "forcestop" -> {
                if (manager.forceStopEvent()) {
                    chat.sendPrefixed(sender, "§cVampire Hunt force-stopped.");
                } else {
                    chat.sendPrefixed(sender, "§cNo active or queued event to force-stop.");
                }
            }

            case "reload" -> {
                plugin.reloadConfig();
                plugin.getEventStatsManager().load();
                chat.sendPrefixed(sender, "§aConfiguration and player stats reloaded.");
            }

            // ── Status ────────────────────────────────────────────────────────
            case "status" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("  §4§lVampire Hunt Admin §8— §7Live Status");
                sender.sendMessage("");
                sender.sendMessage("  §7Phase:          §f" + manager.getPhaseName());
                sender.sendMessage("  §7Queued Players: §f" + manager.getQueuedCount());
                sender.sendMessage("  §7Ready Players:  §f" + manager.getReadyCount());
                sender.sendMessage("  §7Active Players: §f" + manager.getActiveCount());
                sender.sendMessage("  §7Vampires Alive: §5" + manager.getVampireCount());
                sender.sendMessage("  §7Hunters Alive:  §b" + manager.getHunterCount());
                sender.sendMessage(LINE);
            }

            // ── Spawn setup ───────────────────────────────────────────────────
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly a player can set spawn points.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(LINE);
                    player.sendMessage("  §4§lVampire Hunt Admin §8— §7Spawn Setup");
                    player.sendMessage("");
                    player.sendMessage("  §7Stand at the location you want to set, then:");
                    player.sendMessage(ARROW + "§f/" + label + " setspawn lobby     §8│ §7Queue / waiting area");
                    player.sendMessage(ARROW + "§f/" + label + " setspawn vampire   §8│ §7Vampire team spawn");
                    player.sendMessage(ARROW + "§f/" + label + " setspawn hunter    §8│ §7Hunter team spawn");
                    player.sendMessage(ARROW + "§f/" + label + " setspawn return    §8│ §7Post-event return point");
                    player.sendMessage(ARROW + "§f/" + label + " setspawn spectator §8│ §7Spectator camera perch");
                    player.sendMessage(LINE);
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
                    chat.sendPrefixed(player, "§cUnknown spawn type. Valid: lobby, vampire, hunter, return, spectator.");
                    return true;
                }
                plugin.getEventArenaManager().setLocation(configPath, player.getLocation());
                chat.sendPrefixed(player, "§aSpawn §f" + spawnType + " §aset to your current location.");
            }

            // ── In-event role management ──────────────────────────────────────
            case "addvampire" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: §f/" + label + " addvampire <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                chat.sendPrefixed(sender, manager.adminForceVampire(target));
            }

            case "removevampire" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: §f/" + label + " removevampire <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                chat.sendPrefixed(sender, manager.adminForceHunter(target));
            }

            case "addplayer" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: §f/" + label + " addplayer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                if (manager.joinEvent(target)) {
                    chat.sendPrefixed(sender, "§aForce-added §f" + target.getName() + " §ato the event queue.");
                } else {
                    chat.sendPrefixed(sender, "§eCould not add §f" + target.getName() + " §e(may already be in the event).");
                }
            }

            case "removeplayer" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: §f/" + label + " removeplayer <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    chat.sendPrefixed(sender, "§cPlayer §f" + args[1] + " §cis not online.");
                    return true;
                }
                if (manager.leaveEvent(target, true)) {
                    chat.sendPrefixed(sender, "§aRemoved §f" + target.getName() + " §afrom the event.");
                } else {
                    chat.sendPrefixed(sender, "§ePlayer §f" + target.getName() + " §ewas not in the event.");
                }
            }

            // ── Player lists ──────────────────────────────────────────────────
            case "listplayers" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("  §4§lVampire Hunt Admin §8— §7Active Players");
                sender.sendMessage(manager.getAdminPlayerListMessage());
                sender.sendMessage(LINE);
            }

            // ── Leaderboard ───────────────────────────────────────────────────
            case "leaderboard", "lb", "top" -> {
                String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "wins";
                sendLeaderboard(sender, category);
            }

            // ── Stats for any player ──────────────────────────────────────────
            case "stats" -> {
                if (args.length < 2) {
                    chat.sendPrefixed(sender, "§cUsage: §f/" + label + " stats <player>");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Help — sectioned admin overview
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(LINE);
        sender.sendMessage("  §4§lVampire Hunt Admin §8— §7Command Reference  §8[§f/" + label + "§8]");
        sender.sendMessage("");

        sender.sendMessage("§6§lEvent Lifecycle");
        sender.sendMessage(ARROW + "§f/" + label + " start        §8│ §7Force-start countdown (skip ready check)");
        sender.sendMessage(ARROW + "§f/" + label + " stop         §8│ §7Stop gracefully (restores all players)");
        sender.sendMessage(ARROW + "§f/" + label + " forcestop    §8│ §7Immediately stop and restore all players");
        sender.sendMessage(ARROW + "§f/" + label + " reload       §8│ §7Reload config.yml and player stats");
        sender.sendMessage(ARROW + "§f/" + label + " status       §8│ §7Live phase, queue size, team counts");
        sender.sendMessage("");

        sender.sendMessage("§6§lArena Setup");
        sender.sendMessage(ARROW + "§f/" + label + " setspawn <type>   §8│ §7Set a spawn at your location");
        sender.sendMessage("    §8Types: §flobby §8· §fvampire §8· §fhunter §8· §freturn §8· §fspectator");
        sender.sendMessage("    §8Tip: run §f/" + label + " setspawn §8alone for a guided list.");
        sender.sendMessage("");

        sender.sendMessage("§6§lPlayer Management");
        sender.sendMessage(ARROW + "§f/" + label + " addplayer <p>      §8│ §7Force-add player to the queue");
        sender.sendMessage(ARROW + "§f/" + label + " removeplayer <p>   §8│ §7Force-remove player from the event");
        sender.sendMessage(ARROW + "§f/" + label + " addvampire <p>     §8│ §7Switch a player to the vampire team");
        sender.sendMessage(ARROW + "§f/" + label + " removevampire <p>  §8│ §7Switch a vampire back to hunter");
        sender.sendMessage(ARROW + "§f/" + label + " listplayers        §8│ §7List all current participants by role");
        sender.sendMessage("");

        sender.sendMessage("§6§lStats & Leaderboards");
        sender.sendMessage(ARROW + "§f/" + label + " stats <player>          §8│ §7Full stats for any player");
        sender.sendMessage(ARROW + "§f/" + label + " leaderboard [category]  §8│ §7Top-10 leaderboard");
        sender.sendMessage("    §8Categories: §fwins §8· §fkills §8· §fhunterkills §8· §finfections §8· §fevents §8· §fstreak");
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leaderboard
    // ─────────────────────────────────────────────────────────────────────────

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

        sender.sendMessage(LINE);
        sender.sendMessage("  §4§lVampire Hunt §8— §7Top " + limit + " by §f" + catLabel);
        sender.sendMessage("");
        for (int i = 0; i < limit; i++) {
            Entry e = entries.get(i);
            String medal = switch (i) {
                case 0  -> "§6§l#1 ";
                case 1  -> "§7§l#2 ";
                case 2  -> "§c§l#3 ";
                default -> "§8#" + (i + 1) + " ";
            };
            sender.sendMessage("  " + medal + "§f" + e.name() + " §8— §e" + e.value());
        }
        sender.sendMessage("");
        sender.sendMessage("  §8Filter: §f/" + "vhadmin" + " leaderboard §8[wins|kills|hunterkills|infections|events|streak]");
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
        sender.sendMessage("  §4§lVampire Hunt Admin §8— §7Stats: §f" + targetName);
        sender.sendMessage("");
        if (!primaryTitle.isBlank()) {
            sender.sendMessage("  §7Title:          §d" + primaryTitle);
        }
        sender.sendMessage("  §7Events Played:  §f" + stats.getEventsPlayed());
        sender.sendMessage("  §aWins: §f" + stats.getWins()
                + "  §cLosses: §f" + stats.getLosses()
                + "  §eWin Rate: §f" + PCT.format(winRate) + "%");
        sender.sendMessage("  §5Vampire Wins:   §f" + stats.getVampireWins()
                + "   §bHunter Wins: §f" + stats.getHunterWins());
        sender.sendMessage("  §5Vampire Kills:  §f" + stats.getVampireKills()
                + "   §bHunter Kills: §f" + stats.getHunterKills()
                + "   §4Infections: §f" + stats.getInfections());
        sender.sendMessage("  §eStreak: §f" + stats.getCurrentWinStreak()
                + "  §6Best: §f" + stats.getBestWinStreak());
        List<String> titles = statsManager.getUnlockedTitles(targetId);
        sender.sendMessage("  §7Titles: §d" + (titles.isEmpty() ? "§8None" : String.join("§7, §d", titles)));
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
