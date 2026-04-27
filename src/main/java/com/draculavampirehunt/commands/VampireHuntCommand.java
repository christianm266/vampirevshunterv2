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

    private static final String LINE  = "\u00a78\u00a7m----------------------------------------------------";
    private static final String ARROW = "  \u00a78\u00bb ";
    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("0.00");

    private final DraculaVampireHunt plugin;

    public VampireHuntCommand(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Command dispatch
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ChatManager chat = plugin.getChatManager();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!sender.hasPermission("draculavampirehunt.use")) {
            chat.sendPrefixed(sender, "\u00a7cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label, manager);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {

            case "help" -> sendHelp(sender, label, manager);

            case "join" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can join the event.");
                    return true;
                }
                manager.joinEvent(player);
            }

            case "leave" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can leave the event.");
                    return true;
                }
                if (!manager.leaveEvent(player, true)) {
                    chat.sendPrefixed(player, "\u00a7eYou are not in the event.");
                }
            }

            case "ready" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can ready up.");
                    return true;
                }
                if (!manager.setPlayerReady(player)) {
                    chat.sendPrefixed(player, "\u00a7eYou must be in the event queue to ready up.");
                }
            }

            case "unready" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can unready.");
                    return true;
                }
                if (!manager.unreadyPlayer(player)) {
                    chat.sendPrefixed(player, "\u00a7eYou were not marked ready.");
                }
            }

            case "class" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can select a class.");
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
                    chat.sendPrefixed(sender, "\u00a7cOnly players can use a class ability.");
                    return true;
                }
                if (!manager.isActiveParticipant(player.getUniqueId())) {
                    chat.sendPrefixed(player, "\u00a7cYou must be an active event participant to use your ability.");
                    return true;
                }
                RoleClass roleClass = manager.getSelectedClass(player.getUniqueId());
                if (roleClass == RoleClass.NONE) {
                    chat.sendPrefixed(player, "\u00a7cNo class selected. Use \u00a7f/" + label + " class\u00a7c to pick one.");
                    return true;
                }
                if (!manager.useClassAbility(player)) {
                    chat.sendPrefixed(player, "\u00a7eAbility not available right now.");
                }
            }

            case "teams" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("\u00a74\u00a7lVampire Hunt \u00a78\u00bb \u00a77Live Teams");
                sender.sendMessage(manager.getTeamCountLine());
                sender.sendMessage(LINE);
            }

            case "specteleport" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can spectate-teleport.");
                    return true;
                }
                if (!manager.isSpectatorParticipant(player.getUniqueId())) {
                    chat.sendPrefixed(player, "\u00a7cYou must be in spectator mode to use this.");
                    return true;
                }
                if (args.length < 2) {
                    chat.sendPrefixed(player, "\u00a7cUsage: \u00a7f/" + label + " specteleport <next|hunters|vampires>");
                    return true;
                }
                String mode = args[1].toLowerCase(Locale.ROOT);
                Player target = switch (mode) {
                    case "hunters", "hunter"   -> manager.getNextSpectatorTarget(player, false, true);
                    case "vampires", "vampire" -> manager.getNextSpectatorTarget(player, true, false);
                    default                    -> manager.getNextSpectatorTarget(player, false, false);
                };
                if (target == null) {
                    chat.sendPrefixed(player, "\u00a7eNo valid spectator target found.");
                    return true;
                }
                player.setSpectatorTarget(target);
                chat.sendPrefixed(player, "\u00a7aNow spectating \u00a7f" + target.getName() + "\u00a7a.");
            }

            case "status", "phase" -> {
                sender.sendMessage(LINE);
                sender.sendMessage("\u00a74\u00a7lVampire Hunt \u00a78\u00bb \u00a77Event Status");
                sender.sendMessage("  \u00a77Phase:          \u00a7f" + manager.getPhaseName());
                sender.sendMessage("  \u00a77Queued Players: \u00a7f" + manager.getQueuedCount());
                sender.sendMessage("  \u00a77Ready Players:  \u00a7f" + manager.getReadyCount());
                sender.sendMessage("  \u00a77Active Players: \u00a7f" + manager.getActiveCount());
                sender.sendMessage("  \u00a77Vampires Alive: \u00a75" + manager.getVampireCount());
                sender.sendMessage("  \u00a77Hunters Alive:  \u00a7b" + manager.getHunterCount());
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
                    chat.sendPrefixed(sender, "\u00a7cConsole must specify a player: \u00a7f/" + label + " stats <player>");
                    return true;
                }
                sendStats(sender, player.getUniqueId(), player.getName());
            }

            case "info" -> sendEventInfo(sender, label);

            case "vote" -> {
                if (!(sender instanceof Player player)) {
                    chat.sendPrefixed(sender, "\u00a7cOnly players can vote.");
                    return true;
                }
                VoteManager voteManager = manager.getVoteManager();
                if (!voteManager.isVoteOpen()) {
                    chat.sendPrefixed(player, "\u00a7eThere is no active vote right now. Votes open after each round ends.");
                    return true;
                }
                if (args.length < 2) {
                    sendVoteHelp(player, label, voteManager);
                    return true;
                }
                if (voteManager.castVote(player.getUniqueId(), args[1])) {
                    chat.sendPrefixed(player, "\u00a7aVote cast! Tally: \u00a7f" + voteManager.getTallyDisplay());
                } else {
                    chat.sendPrefixed(player, "\u00a7cInvalid option. Choose: \u00a7f" + voteManager.getOptionsDisplay());
                }
            }

            default -> {
                // Suggest the closest valid subcommand instead of a bare error
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

    // ───────────────────────────────────────────────────────────────────────────
    // Closest subcommand suggestion (simple prefix match)
    // ───────────────────────────────────────────────────────────────────────────

    private static final List<String> ALL_SUBS = List.of(
            "help", "join", "leave", "ready", "unready",
            "class", "ability", "teams", "specteleport",
            "status", "stats", "info", "vote"
    );

    private String findClosestSub(String input) {
        if (input == null || input.isBlank()) return null;
        String lower = input.toLowerCase(Locale.ROOT);
        // prefix match first
        for (String s : ALL_SUBS) {
            if (s.startsWith(lower)) return s;
        }
        // contains match fallback
        for (String s : ALL_SUBS) {
            if (s.contains(lower)) return s;
        }
        return null;
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Help — fully role-aware
    // ───────────────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender, String label, VampireHuntManager manager) {
        boolean isPlayer    = sender instanceof Player;
        UUID    pid         = isPlayer ? ((Player) sender).getUniqueId() : null;
        boolean inEvent     = isPlayer && manager.isEventParticipant(pid);
        boolean isActive    = isPlayer && manager.isActiveParticipant(pid);
        boolean isVampire   = isPlayer && manager.isVampire(pid);
        boolean isHunter    = isPlayer && manager.isHunter(pid);
        boolean isSpectator = isPlayer && manager.isSpectatorParticipant(pid);
        boolean inQueue     = inEvent && !isActive && !isSpectator;
        RoleClass myClass   = isPlayer ? manager.getSelectedClass(pid) : RoleClass.NONE;

        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt \u00a78\u2014 \u00a77Commands  \u00a78[\u00a7f/" + label + "\u00a78]");
        sender.sendMessage("");

        // ── Role/State context banner ──────────────────────────────────────────
        if (isVampire) {
            String classLine = myClass != RoleClass.NONE ? "  \u00a78Class: \u00a7f" + myClass.getDisplayName() : "  \u00a78No class selected.");
            sender.sendMessage("  \u00a75\u25cf \u00a7lVAMPIRE\u00a7r\u00a75 — Convert all hunters to win the round.");
            sender.sendMessage(classLine);
            sender.sendMessage("  \u00a77Use \u00a7f/" + label + " class\u00a77 to switch class. Use \u00a7f/" + label + " ability\u00a77 to activate it.");
        } else if (isHunter) {
            String classLine = myClass != RoleClass.NONE ? "  \u00a78Class: \u00a7f" + myClass.getDisplayName() : "  \u00a78No class selected.";
            sender.sendMessage("  \u00a7b\u25cf \u00a7lHUNTER\u00a7r\u00a7b — Kill all vampires to win the round.");
            sender.sendMessage(classLine);
            sender.sendMessage("  \u00a77Use \u00a7f/" + label + " class\u00a77 to switch class. Use \u00a7f/" + label + " ability\u00a77 to activate it.");
        } else if (isSpectator) {
            sender.sendMessage("  \u00a77\u25cf \u00a7lSPECTATING\u00a7r\u00a77 — Watch the round from above.");
            sender.sendMessage("  \u00a77Vote on the next round modifier, or teleport to a player.");
        } else if (inQueue) {
            sender.sendMessage("  \u00a7e\u25cf \u00a7lQUEUED\u00a7r\u00a7e — Waiting in lobby. Use \u00a7f/" + label + " ready\u00a7e to confirm.");
        } else {
            sender.sendMessage("  \u00a77Vampires infect hunters. Hunters must eliminate all vampires to win.");
            sender.sendMessage("  \u00a77Use \u00a7f/" + label + " info\u00a77 for full rules and modifiers.");
        }
        sender.sendMessage("");

        // ── Section: Joining (only shown if NOT active in a round) ─────────────
        if (!isActive) {
            sender.sendMessage("\u00a76\u00a7lJoining & Queue");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " join     \u00a78\u2502 \u00a77Enter the event queue");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " leave    \u00a78\u2502 \u00a77Leave queue, event, or spectator mode");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " ready    \u00a78\u2502 \u00a77Mark yourself ready before the countdown");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " unready  \u00a78\u2502 \u00a77Withdraw your ready state");
            sender.sendMessage("");
        }

        // ── Section: Classes — only what's relevant to your role ───────────────
        if (isActive || !isSpectator) {
            if (isVampire) {
                sender.sendMessage("\u00a75\u00a7lVampire Classes");
                sendVampireClassLines(sender, label);
            } else if (isHunter) {
                sender.sendMessage("\u00a7b\u00a7lHunter Classes");
                sendHunterClassLines(sender, label);
            } else {
                // Not yet in a round — show both with a header
                sender.sendMessage("\u00a76\u00a7lClasses  \u00a78(\u00a77choose before the round starts\u00a78)");
                sender.sendMessage(ARROW + "\u00a7f/" + label + " class <name>  \u00a78\u2502 \u00a77Select your class");
                sender.sendMessage(ARROW + "\u00a7f/" + label + " ability       \u00a78\u2502 \u00a77Use your active class ability in-game");
                sender.sendMessage("");
                sender.sendMessage("  \u00a75Vampire: \u00a7fstalker\u00a78, \u00a7fbrute");
                sender.sendMessage("  \u00a7bHunter:  \u00a7ftracker\u00a78, \u00a7fpriest");
                sender.sendMessage("  \u00a77Run \u00a7f/" + label + " class\u00a77 alone to see full descriptions.");
            }
            sender.sendMessage("");
        }

        // ── Section: In-event actions (only shown when active) ─────────────────
        if (isActive) {
            sender.sendMessage("\u00a76\u00a7lIn-Round Commands");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " class <name>  \u00a78\u2502 \u00a77Switch your class mid-round");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " ability       \u00a78\u2502 \u00a77Activate your class ability");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " teams         \u00a78\u2502 \u00a77Live hunter/vampire counts");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " leave         \u00a78\u2502 \u00a77Forfeit and leave the event");
            sender.sendMessage("");
        }

        // ── Section: Spectator commands (shown when spectating or voting open) ──
        if (isSpectator) {
            sender.sendMessage("\u00a76\u00a7lSpectator Commands");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " specteleport next      \u00a78\u2502 \u00a77Cycle to the next active player");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " specteleport hunters   \u00a78\u2502 \u00a77Cycle through hunters only");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " specteleport vampires  \u00a78\u2502 \u00a77Cycle through vampires only");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " leave                  \u00a78\u2502 \u00a77Leave spectator (loses payout)");
            sender.sendMessage("");

            sender.sendMessage("\u00a76\u00a7lVoting");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " vote             \u00a78\u2502 \u00a77See current vote options and tally");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " vote <option>    \u00a78\u2502 \u00a77Cast your vote");
            sender.sendMessage("  \u00a78Options: \u00a7fdouble \u00a78\u00b7 \u00a7fnocompass \u00a78\u00b7 \u00a7fsd \u00a78\u00b7 \u00a7ffog \u00a78\u00b7 \u00a7fnone");
            sender.sendMessage("");
        }

        // ── Section: Info & Stats (always shown) ───────────────────────────────
        sender.sendMessage("\u00a76\u00a7lInfo & Stats");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " status          \u00a78\u2502 \u00a77Current phase and team counts");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " info            \u00a78\u2502 \u00a77Full rules, powers, and modifiers");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " stats           \u00a78\u2502 \u00a77Your wins, kills, titles");
        if (!isActive && !isSpectator) {
            sender.sendMessage(ARROW + "\u00a7f/" + label + " teams           \u00a78\u2502 \u00a77Live team counts during a round");
            sender.sendMessage(ARROW + "\u00a7f/" + label + " vote            \u00a78\u2502 \u00a77Vote between rounds (spectators only)");
        }
        sender.sendMessage(LINE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Class help — role-aware, shown when /vhunt class used with no argument
    // ───────────────────────────────────────────────────────────────────────────

    private void sendClassHelp(Player player, String label, VampireHuntManager manager) {
        boolean isVampire = manager.isVampire(player.getUniqueId());
        boolean isHunter  = manager.isHunter(player.getUniqueId());
        RoleClass current = manager.getSelectedClass(player.getUniqueId());

        player.sendMessage(LINE);
        player.sendMessage("  \u00a74\u00a7lVampire Hunt \u00a78\u2014 \u00a77Classes");
        if (current != RoleClass.NONE) {
            player.sendMessage("  \u00a77Active class: \u00a7f" + current.getDisplayName() + "\u00a77. Use \u00a7f/" + label + " ability\u00a77 to trigger it.");
        }
        player.sendMessage("");

        if (isVampire) {
            // Vampire only
            player.sendMessage("\u00a75\u00a7lVampire Classes");
            sendVampireClassLines(player, label);
            player.sendMessage("");
            player.sendMessage("  \u00a77Usage: \u00a7f/" + label + " class <stalker|brute>");
        } else if (isHunter) {
            // Hunter only
            player.sendMessage("\u00a7b\u00a7lHunter Classes");
            sendHunterClassLines(player, label);
            player.sendMessage("");
            player.sendMessage("  \u00a77Usage: \u00a7f/" + label + " class <tracker|priest>");
        } else {
            // Not yet in a round — show both
            player.sendMessage("\u00a75\u00a7lVampire Classes");
            sendVampireClassLines(player, label);
            player.sendMessage("");
            player.sendMessage("\u00a7b\u00a7lHunter Classes");
            sendHunterClassLines(player, label);
            player.sendMessage("");
            player.sendMessage("  \u00a77Usage: \u00a7f/" + label + " class <stalker|brute|tracker|priest>");
        }

        player.sendMessage("  \u00a77Activate ability in-game: \u00a7f/" + label + " ability");
        player.sendMessage(LINE);
    }

    private void sendVampireClassLines(CommandSender s, String label) {
        s.sendMessage(ARROW + "\u00a7fstalker  \u00a78\u2502 \u00a77Passive: +15 s start invis, +5 s kill invis");
        s.sendMessage("    \u00a78Active: \u00a7fStalker Veil \u00a78\u2014 \u00a7710 s invisibility \u00a78(30 s cooldown)");
        s.sendMessage(ARROW + "\u00a7fbrute    \u00a78\u2502 \u00a77Passive: none");
        s.sendMessage("    \u00a78Active: \u00a7fBrute Rage \u00a78\u2014 \u00a77Strength I + Speed I for 8 s \u00a78(40 s cooldown)");
    }

    private void sendHunterClassLines(CommandSender s, String label) {
        s.sendMessage(ARROW + "\u00a7ftracker  \u00a78\u2502 \u00a77Passive: enhanced compass lore");
        s.sendMessage("    \u00a78Active: \u00a7fTracker Pulse \u00a78\u2014 \u00a77Pin compass to nearest vampire \u00a78(15 s cooldown)");
        s.sendMessage(ARROW + "\u00a7fpriest   \u00a78\u2502 \u00a77Passive: on-hit reveals vampires in 20-block radius");
        s.sendMessage("    \u00a78Active: \u00a7fHoly Reveal \u00a78\u2014 \u00a77Expose vampires within 28 blocks \u00a78(35 s cooldown)");
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Vote help — shown when /vhunt vote is used with no argument
    // ───────────────────────────────────────────────────────────────────────────

    private void sendVoteHelp(Player player, String label, VoteManager voteManager) {
        player.sendMessage(LINE);
        player.sendMessage("  \u00a74\u00a7lVampire Hunt \u00a78\u2014 \u00a77Round Modifier Vote");
        player.sendMessage("");
        player.sendMessage("  \u00a77Vote for the modifier that applies to the NEXT round:");
        player.sendMessage("");
        player.sendMessage(ARROW + "\u00a7fdouble     \u00a78\u2502 \u00a77Double the number of starting vampires");
        player.sendMessage(ARROW + "\u00a7fnocompass  \u00a78\u2502 \u00a77Hunters do not receive a tracker compass");
        player.sendMessage(ARROW + "\u00a7fsd         \u00a78\u2502 \u00a77Sudden Death — round timer is halved");
        player.sendMessage(ARROW + "\u00a7ffog        \u00a78\u2502 \u00a77Fog of War — no opening vampire reveal");
        player.sendMessage(ARROW + "\u00a7fnone       \u00a78\u2502 \u00a77No modifier (normal round)");
        player.sendMessage("");
        player.sendMessage("  \u00a77Current tally: \u00a7f" + voteManager.getTallyDisplay());
        player.sendMessage("  \u00a77Usage: \u00a7f/" + label + " vote <option>");
        player.sendMessage(LINE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Event info
    // ───────────────────────────────────────────────────────────────────────────

    private void sendEventInfo(CommandSender sender, String label) {
        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt \u00a78\u2014 \u00a77How It Works");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lWin Conditions");
        sender.sendMessage(ARROW + "\u00a7bHunters \u00a77win by killing all vampires.");
        sender.sendMessage(ARROW + "\u00a75Vampires \u00a77win by converting (infecting) all hunters.");
        sender.sendMessage(ARROW + "\u00a77If time runs out \u00a78\u2192 \u00a7cSudden Death\u00a77: hunters glow, vampires gain Strength I.");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lVampire Powers");
        sender.sendMessage(ARROW + "\u00a77Permanent \u00a7fNight Vision\u00a77.");
        sender.sendMessage(ARROW + "\u00a77Invisible at round start (\u226560 s). \u00a7cArmor is still visible!");
        sender.sendMessage(ARROW + "\u00a77Gain brief invisibility after each kill.");
        sender.sendMessage(ARROW + "\u00a77Killing a hunter \u00a75infects\u00a77 them — they switch to the vampire team.");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lHunter Advantages");
        sender.sendMessage(ARROW + "\u00a77Compass automatically tracks the nearest vampire.");
        sender.sendMessage(ARROW + "\u00a77Heartbeat sound when a vampire is within 12 blocks.");
        sender.sendMessage(ARROW + "\u00a77Staying in one spot too long causes \u00a7cGlowing\u00a77 — move around!");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lReady Check");
        sender.sendMessage(ARROW + "\u00a7f/" + label + " join\u00a77 to enter the queue, then \u00a7f/" + label + " ready\u00a77 to confirm.");
        sender.sendMessage(ARROW + "\u00a77Countdown starts once all queued players are ready.");
        sender.sendMessage(ARROW + "\u00a77Vampires are briefly \u00a7fglowing\u00a77 at the start for identification.");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lRound Modifiers  \u00a78(\u00a77voted by spectators between rounds\u00a78)");
        sender.sendMessage(ARROW + "\u00a7fdouble     \u00a78\u2014 \u00a77Double starting vampires");
        sender.sendMessage(ARROW + "\u00a7fnocompass  \u00a78\u2014 \u00a77Hunters start without a tracker compass");
        sender.sendMessage(ARROW + "\u00a7fsd         \u00a78\u2014 \u00a77Sudden Death mode — timer halved");
        sender.sendMessage(ARROW + "\u00a7ffog        \u00a78\u2014 \u00a77No opening vampire reveal");
        sender.sendMessage("");

        sender.sendMessage("\u00a76\u00a7lMVP & Titles");
        sender.sendMessage(ARROW + "\u00a77Top Vampire and top Hunter are announced at round end.");
        sender.sendMessage(ARROW + "\u00a77Earn cosmetic titles shown in \u00a7f/" + label + " stats\u00a77.");
        sender.sendMessage(LINE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Stats
    // ───────────────────────────────────────────────────────────────────────────

    private void sendStats(CommandSender sender, UUID targetId, String targetName) {
        EventStatsManager statsManager = plugin.getEventStatsManager();
        EventStatsManager.PlayerEventStats stats = statsManager.getStats(targetId);
        double winRate = statsManager.getWinRate(targetId) * 100.0D;
        double kd = statsManager.getKillDeathRatio(targetId);

        sender.sendMessage(LINE);
        sender.sendMessage("  \u00a74\u00a7lVampire Hunt \u00a78\u2014 \u00a77Stats: \u00a7f" + (targetName != null ? targetName : targetId.toString()));
        sender.sendMessage("");
        sender.sendMessage("  \u00a77Events played: \u00a7f" + stats.getEventsPlayed());
        sender.sendMessage("  \u00a7aWins: \u00a7f" + stats.getWins()
                + "  \u00a7cLosses: \u00a7f" + stats.getLosses()
                + "  \u00a77Win Rate: \u00a7e" + PERCENT_FORMAT.format(winRate) + "%");
        sender.sendMessage("  \u00a77K/D Ratio: \u00a7f" + PERCENT_FORMAT.format(kd));
        sender.sendMessage("  \u00a77Total Kills: \u00a7f" + stats.getTotalKills()
                + "  \u00a77Deaths: \u00a7f" + stats.getTotalDeaths());
        sender.sendMessage("  \u00a77Infections: \u00a74" + stats.getInfections());
        sender.sendMessage("  \u00a77Vampire Rounds: \u00a75" + stats.getVampireRounds()
                + "  \u00a77Hunter Rounds: \u00a7b" + stats.getHunterRounds());
        if (stats.getBestWinStreak() > 0) {
            sender.sendMessage("  \u00a77Current Streak: \u00a7e" + stats.getCurrentWinStreak()
                    + "  \u00a77Best Streak: \u00a76" + stats.getBestWinStreak());
        }
        List<String> titles = stats.getUnlockedTitles();
        if (!titles.isEmpty()) {
            sender.sendMessage("  \u00a77Titles: \u00a7d" + String.join("\u00a77, \u00a7d", titles));
        }
        sender.sendMessage(LINE);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Tab completion
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("draculavampirehunt.use")) {
            return Collections.emptyList();
        }

        VampireHuntManager manager = plugin.getVampireHuntManager();
        boolean isPlayer    = sender instanceof Player;
        UUID    pid         = isPlayer ? ((Player) sender).getUniqueId() : null;
        boolean isActive    = isPlayer && manager.isActiveParticipant(pid);
        boolean isSpectator = isPlayer && manager.isSpectatorParticipant(pid);

        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("help");
            subs.add("info");
            subs.add("status");
            subs.add("teams");
            subs.add("stats");

            if (!isActive && !isSpectator) {
                subs.add("join");
                subs.add("leave");
                subs.add("ready");
                subs.add("unready");
            }
            if (isActive || !isSpectator) {
                subs.add("class");
                subs.add("ability");
            }
            if (isActive) {
                subs.add("leave");
            }
            if (isSpectator) {
                subs.add("specteleport");
                subs.add("vote");
                subs.add("leave");
            }
            // vote shown to everyone (they'll get a friendly message if no vote is open)
            if (!isSpectator) subs.add("vote");

            return filter(args[0], subs.toArray(new String[0]));
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "class"        -> {
                    boolean isVampire = isPlayer && manager.isVampire(pid);
                    boolean isHunter  = isPlayer && manager.isHunter(pid);
                    if (isVampire)       yield filter(args[1], "stalker", "brute");
                    else if (isHunter)   yield filter(args[1], "tracker", "priest");
                    else                 yield filter(args[1], "stalker", "brute", "tracker", "priest");
                }
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
