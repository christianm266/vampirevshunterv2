package com.draculavampirehunt.commands;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.ChatManager;
import com.draculavampirehunt.managers.EventStatsManager;
import com.draculavampirehunt.managers.RoleClass;
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

    private static final String LINE  = "§8§m----------------------------------------------------";
    private static final String ARROW = "  §8» ";
    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("0.00");

    private final DraculaVampireHunt plugin;

    public VampireHuntCommand(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Command dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ChatManager chat = plugin.getChatManager();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!sender.hasPermission("draculavampirehunt.use")) {
            chat.sendPrefixed(sender, "§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label, manager);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {

            case "help" -> {
                sendHelp(sender, label, manager);
            }

            case "join" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can join the event.");
                    return true;
                }
                manager.joinEvent(player);
            }

            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can leave the event.");
                    return true;
                }
                if (!manager.leaveEvent(player, true)) {
                    chat.sendPrefixed(player, "§eYou are not in the event.");
                }
            }

            case "ready" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can ready up.");
                    return true;
                }
                if (!manager.setPlayerReady(player)) {
                    chat.sendPrefixed(player, "§eYou must be in the event queue to ready up.");
                }
            }

            case "unready" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can unready.");
                    return true;
                }
                if (!manager.unreadyPlayer(player)) {
                    chat.sendPrefixed(player, "§eYou were not marked ready.");
                }
            }

            case "class" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can select a class.");
                    return true;
                }
                if (args.length < 2) {
                    sendClassHelp(player, label, manager);
                    return true;
                }
                manager.selectClass(player, args[1]);
            }

            case "ability" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can use a class ability.");
                    return true;
                }
                if (!manager.isActiveParticipant(player.getUniqueId())) {
                    chat.sendPrefixed(player, "§cYou must be an active event participant to use your ability.");
                    return true;
                }
                RoleClass roleClass = manager.getSelectedClass(player.getUniqueId());
                if (roleClass == RoleClass.NONE) {
                    chat.sendPrefixed(player, "§cYou have no class selected. Use §f/" + label + " class§c to pick one.");
                    return true;
                }
                if (!manager.useClassAbility(player)) {
                    chat.sendPrefixed(player, "§eAbility not available right now.");
                }
            }

            case "teams" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("§4§lVampire Hunt §8» §7Live Teams");
                sender.sendMessage(manager.getTeamCountLine());
                sender.sendMessage(LINE);
            }

            case "specteleport" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can spectate-teleport.");
                    return true;
                }
                if (!manager.isSpectatorParticipant(player.getUniqueId())) {
                    chat.sendPrefixed(player, "§cYou must be in spectator mode to use this.");
                    return true;
                }
                if (args.length < 2) {
                    chat.sendPrefixed(player, "§cUsage: §f/" + label + " specteleport <next|hunters|vampires>");
                    return true;
                }
                String mode = args[1].toLowerCase(Locale.ROOT);
                Player target = switch (mode) {
                    case "hunters", "hunter"   -> manager.getNextSpectatorTarget(player, false, true);
                    case "vampires", "vampire" -> manager.getNextSpectatorTarget(player, true, false);
                    default                    -> manager.getNextSpectatorTarget(player, false, false);
                };
                if (target == null) {
                    chat.sendPrefixed(player, "§eNo valid spectator target found.");
                    return true;
                }
                player.setSpectatorTarget(target);
                chat.sendPrefixed(player, "§aNow spectating §f" + target.getName() + "§a.");
            }

            case "status", "phase" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("§4§lVampire Hunt §8» §7Event Status");
                sender.sendMessage("  §7Phase:          §f" + manager.getPhaseName());
                sender.sendMessage("  §7Queued Players: §f" + manager.getQueuedCount());
                sender.sendMessage("  §7Ready Players:  §f" + manager.getReadyCount());
                sender.sendMessage("  §7Active Players: §f" + manager.getActiveCount());
                sender.sendMessage("  §7Vampires Alive: §5" + manager.getVampireCount());
                sender.sendMessage("  §7Hunters Alive:  §b" + manager.getHunterCount());
                sender.sendMessage(LINE);
            }

            case "stats" -> {
                if (args.length >= 2 && sender.hasPermission("draculavampirehunt.admin.stats")) {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    sendStats(sender, target.getUniqueId(), target.getName());
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cConsole must specify a player: §f/" + label + " stats <player>");
                    return true;
                }
                sendStats(sender, player.getUniqueId(), player.getName());
            }

            case "info" -> {
                sendEventInfo(sender);
            }

            case "vote" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "§cOnly players can vote.");
                    return true;
                }
                VoteManager voteManager = manager.getVoteManager();
                if (!voteManager.isVoteOpen()) {
                    chat.sendPrefixed(player, "§eThere is no active vote right now. Votes open after each round ends.");
                    return true;
                }
                if (args.length < 2) {
                    chat.sendPrefixed(player, "§6Vote for the next round modifier:");
                    chat.sendPrefixed(player, "§7Options: §f" + voteManager.getOptionsDisplay());
                    chat.sendPrefixed(player, "§7Usage: §f/" + label + " vote <option>");
                    chat.sendPrefixed(player, "§7Current tally: §f" + voteManager.getTallyDisplay());
                    return true;
                }
                if (voteManager.castVote(player.getUniqueId(), args[1])) {
                    chat.sendPrefixed(player, "§aVote cast! Tally: §f" + voteManager.getTallyDisplay());
                } else {
                    chat.sendPrefixed(player, "§cInvalid option. Choose: §f" + voteManager.getOptionsDisplay());
                }
            }

            default -> {
                chat.sendPrefixed(sender, "§cUnknown subcommand §f'" + args[0] + "'§c. Type §f/" + label + " help §cfor a full list.");
            }
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Help — context-aware (role shown if in event)
    // ─────────────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String label, VampireHuntManager manager) {
        boolean isPlayer    = sender instanceof Player;
        boolean inQueue     = isPlayer && manager.isEventParticipant(((Player) sender).getUniqueId());
        boolean isVampire   = isPlayer && manager.isVampire(((Player) sender).getUniqueId());
        boolean isHunter    = isPlayer && manager.isHunter(((Player) sender).getUniqueId());
        boolean isSpectator = isPlayer && manager.isSpectatorParticipant(((Player) sender).getUniqueId());

        sender.sendMessage(LINE);
        sender.sendMessage("  §4§lVampire Hunt §8— §7Player Help  §8[§f/" + label + "§8]");

        // ── Context banner ────────────────────────────────────────────────────
        if (isVampire) {
            sender.sendMessage("  §5● You are a §lVampire§r§5. Convert all hunters to win.");
        } else if (isHunter) {
            sender.sendMessage("  §b● You are a §lHunter§r§b. Kill all vampires to win.");
        } else if (isSpectator) {
            sender.sendMessage("  §7● You are §lSpectating§r§7. Vote for the next round modifier.");
        } else if (inQueue) {
            sender.sendMessage("  §e● You are §lQueued§r§e. Mark ready when the lobby fills.");
        } else {
            sender.sendMessage("  §7Vampires infect hunters — hunters must eliminate all vampires.");
        }

        sender.sendMessage("");

        // ── Joining & Queue ───────────────────────────────────────────────────
        sender.sendMessage("§6§lJoining & Queue");
        sender.sendMessage(ARROW + "§f/" + label + " join     §8│ §7Enter the event queue");
        sender.sendMessage(ARROW + "§f/" + label + " leave    §8│ §7Leave queue, active event, or spectator mode");
        sender.sendMessage(ARROW + "§f/" + label + " ready    §8│ §7Mark yourself ready before the countdown");
        sender.sendMessage(ARROW + "§f/" + label + " unready  §8│ §7Withdraw your ready state");
        sender.sendMessage("");

        // ── Classes ───────────────────────────────────────────────────────────
        sender.sendMessage("§6§lClasses  §8(§7pick before or during the event§8)");
        sender.sendMessage(ARROW + "§f/" + label + " class <name>  §8│ §7Select your class");
        sender.sendMessage(ARROW + "§f/" + label + " ability       §8│ §7Activate your class active ability");
        sender.sendMessage("");

        // Show class details relevant to the player's current role, or both if idle
        if (isVampire || (!isHunter && !isSpectator)) {
            sender.sendMessage("  §5Vampire Classes:");
            sender.sendMessage("    §f/" + label + " class stalker §8» §7Passive: +15 s start invis, +5 s kill invis");
            sender.sendMessage("    §8  Active: §fStalker Veil §8— §710 s invisibility §8(30 s cd)");
            sender.sendMessage("    §f/" + label + " class brute   §8» §7Passive: none");
            sender.sendMessage("    §8  Active: §fBrute Rage §8— §7Strength I + Speed I for 8 s §8(40 s cd)");
        }
        if (isHunter || (!isVampire && !isSpectator)) {
            sender.sendMessage("  §bHunter Classes:");
            sender.sendMessage("    §f/" + label + " class tracker §8» §7Passive: enhanced compass lore");
            sender.sendMessage("    §8  Active: §fTracker Pulse §8— §7Pin compass to nearest vampire §8(15 s cd)");
            sender.sendMessage("    §f/" + label + " class priest  §8» §7Passive: on-hit reveals vampires in 20-block radius");
            sender.sendMessage("    §8  Active: §fHoly Reveal §8— §7Reveal vampires within 28 blocks §8(35 s cd)");
        }
        sender.sendMessage("");

        // ── In-event ──────────────────────────────────────────────────────────
        sender.sendMessage("§6§lIn-Event Info");
        sender.sendMessage(ARROW + "§f/" + label + " teams   §8│ §7Live hunter / vampire counts");
        sender.sendMessage(ARROW + "§f/" + label + " status  §8│ §7Current phase, queue size, active players");
        sender.sendMessage(ARROW + "§f/" + label + " info    §8│ §7Full event rules and win conditions");
        sender.sendMessage("");

        // ── Spectator ─────────────────────────────────────────────────────────
        sender.sendMessage("§6§lSpectator & Voting");
        sender.sendMessage(ARROW + "§f/" + label + " specteleport <next|hunters|vampires>  §8│ §7Cycle your spectate target");
        sender.sendMessage(ARROW + "§f/" + label + " vote <double|nocompass|sd|fog|none>   §8│ §7Vote for next round modifier");
        sender.sendMessage("");

        // ── Stats ─────────────────────────────────────────────────────────────
        sender.sendMessage("§6§lStats");
        sender.sendMessage(ARROW + "§f/" + label + " stats           §8│ §7View your own stats");
        if (sender.hasPermission("draculavampirehunt.admin.stats")) {
            sender.sendMessage(ARROW + "§f/" + label + " stats <player>  §8│ §7View another player's stats");
        }
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Class help — shown when /vhunt class is used with no argument
    // ─────────────────────────────────────────────────────────────────────────

    private void sendClassHelp(Player player, String label, VampireHuntManager manager) {
        boolean isVampire = manager.isVampire(player.getUniqueId());
        boolean isHunter  = manager.isHunter(player.getUniqueId());
        RoleClass current = manager.getSelectedClass(player.getUniqueId());

        player.sendMessage(LINE);
        player.sendMessage("  §4§lVampire Hunt §8— §7Classes");
        if (current != RoleClass.NONE) {
            player.sendMessage("  §7Current class: §f" + current.getDisplayName());
        }
        player.sendMessage("");

        if (isVampire || (!isHunter)) {
            player.sendMessage("§5Vampire Classes:");
            player.sendMessage(ARROW + "§fstalker §8│ §7Bonus invisibility at start & after kills");
            player.sendMessage("    §8Active: §fStalker Veil §7— 10 s invisibility §8(30 s cd)");
            player.sendMessage(ARROW + "§fbrute   §8│ §7Brute force");
            player.sendMessage("    §8Active: §fBrute Rage §7— Strength I + Speed I (8 s) §8(40 s cd)");
            player.sendMessage("");
        }
        if (isHunter || (!isVampire)) {
            player.sendMessage("§bHunter Classes:");
            player.sendMessage(ARROW + "§ftracker §8│ §7Compass tracks vampires near you");
            player.sendMessage("    §8Active: §fTracker Pulse §7— Pin compass to nearest vampire §8(15 s cd)");
            player.sendMessage(ARROW + "§fpriest  §8│ §7On-hit reveals nearby vampires in 20-block radius");
            player.sendMessage("    §8Active: §fHoly Reveal §7— Expose vampires in 28-block radius §8(35 s cd)");
            player.sendMessage("");
        }
        player.sendMessage("  §7Usage: §f/" + label + " class <stalker|brute|tracker|priest>");
        player.sendMessage("  §7Activate ability: §f/" + label + " ability");
        player.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event info
    // ─────────────────────────────────────────────────────────────────────────

    private void sendEventInfo(CommandSender sender) {
        sender.sendMessage(LINE);
        sender.sendMessage("  §4§lVampire Hunt §8— §7How It Works");
        sender.sendMessage("");
        sender.sendMessage("§6§lWin Conditions");
        sender.sendMessage(ARROW + "§bHunters §7win by killing all vampires.");
        sender.sendMessage(ARROW + "§5Vampires §7win by converting (infecting) all hunters.");
        sender.sendMessage(ARROW + "§7If time runs out → §cSudden Death §7begins.");
        sender.sendMessage("  §8(Hunters glow, vampires gain Strength I until one team falls)");
        sender.sendMessage("");
        sender.sendMessage("§6§lVampire Powers");
        sender.sendMessage(ARROW + "§7Permanent §fNight Vision§7.");
        sender.sendMessage(ARROW + "§7Start with §finvisibility §7(≥60 s). Armor is still visible!");
        sender.sendMessage(ARROW + "§7Gain invisibility after each kill.");
        sender.sendMessage(ARROW + "§7Killing a hunter §5infects §7them — they join the vampire team.");
        sender.sendMessage("");
        sender.sendMessage("§6§lHunter Advantages");
        sender.sendMessage(ARROW + "§7Compass tracks the nearest vampire automatically.");
        sender.sendMessage(ARROW + "§7You are warned (heartbeat sound) when a vampire is within 12 blocks.");
        sender.sendMessage(ARROW + "§7Camping too long causes §cGlowing§7 — vampires can spot you!");
        sender.sendMessage("");
        sender.sendMessage("§6§lReady Check & Countdown");
        sender.sendMessage(ARROW + "§7Use §f/vhunt join §7then §f/vhunt ready§7. Countdown starts when all ready.");
        sender.sendMessage(ARROW + "§7Vampires are briefly §fglowing §7at the start for identification.");
        sender.sendMessage("");
        sender.sendMessage("§6§lSpectators & Voting");
        sender.sendMessage(ARROW + "§7Eliminated players spectate from above the arena.");
        sender.sendMessage(ARROW + "§7Spectators vote on the next round modifier after each round:");
        sender.sendMessage("    §fdouble   §8│ §7Double starting vampires");
        sender.sendMessage("    §fnocompass§8│ §7Hunters get no tracker compass");
        sender.sendMessage("    §fsd       §8│ §7Sudden Death (halved timer)");
        sender.sendMessage("    §ffog      §8│ §7No opening vampire reveal");
        sender.sendMessage("    §fnone     §8│ §7No modifier");
        sender.sendMessage("");
        sender.sendMessage("§6§lMVP & Titles");
        sender.sendMessage(ARROW + "§7At round end, the top Vampire and Hunter are announced.");
        sender.sendMessage(ARROW + "§7Top performers unlock cosmetic titles shown in §f/vhunt stats§7.");
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    private void sendStats(CommandSender sender, UUID targetId, String targetName) {
        EventStatsManager statsManager = plugin.getEventStatsManager();
        EventStatsManager.PlayerEventStats stats = statsManager.getStats(targetId);
        double winRate = statsManager.getWinRate(targetId) * 100.0D;
        double kd = statsManager.getKillDeathRatio(targetId);

        sender.sendMessage(LINE);
        sender.sendMessage("  §4§lVampire Hunt §8— §7Stats: §f" + (targetName != null ? targetName : targetId.toString()));
        sender.sendMessage("");
        sender.sendMessage("  §7Events played: §f" + stats.eventsPlayed);
        sender.sendMessage("  §aWins: §f" + stats.wins + "  §cLosses: §f" + stats.losses
                + "  §7Win Rate: §e" + PERCENT_FORMAT.format(winRate) + "%");
        sender.sendMessage("  §7K/D Ratio: §f" + PERCENT_FORMAT.format(kd));
        sender.sendMessage("  §7Total Kills: §f" + stats.totalKills + "  §7Deaths: §f" + stats.totalDeaths);
        sender.sendMessage("  §7Infections: §4" + stats.infections);
        sender.sendMessage("  §7Vampire Rounds: §5" + stats.vampireRounds
                + "  §7Hunter Rounds: §b" + stats.hunterRounds);
        if (!stats.unlockedTitles.isEmpty()) {
            sender.sendMessage("  §7Titles: §d" + String.join("§7, §d", stats.unlockedTitles));
        }
        sender.sendMessage(LINE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab completion
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("draculavampirehunt.use")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = List.of(
                    "help", "join", "leave", "ready", "unready",
                    "class", "ability", "teams", "specteleport",
                    "status", "stats", "info", "vote"
            );
            return filter(args[0], subs.toArray(new String[0]));
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "class"        -> filter(args[1], "stalker", "brute", "tracker", "priest");
                case "specteleport" -> filter(args[1], "next", "hunters", "vampires");
                case "vote"         -> filter(args[1], "double", "nocompass", "sd", "fog", "none");
                default             -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
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
