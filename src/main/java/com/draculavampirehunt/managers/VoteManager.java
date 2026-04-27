package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spectator-driven voting for next-round modifiers.
 *
 * Usage
 * -----
 * 1. Call {@link #openVote()} shortly after a round ends (e.g. from endEvent).
 * 2. Call {@link #castVote(UUID, String)} when a spectator runs "/vhunt vote <option>".
 * 3. After the voting window closes the winning modifier is automatically applied via
 *    {@link #applyModifier(RoundModifier)}. Query the active modifier at round-start
 *    with {@link #getActiveModifier()}.
 * 4. Call {@link #reset()} after the modifier has been consumed.
 *
 * Available modifiers
 * -------------------
 *   NONE             — standard round (default / tie-break)
 *   DOUBLE_VAMPIRES  — initial vampire count x2
 *   NO_COMPASS       — hunter trackers disabled for the round
 *   SUDDEN_DEATH     — round timer halved; instant sudden-death at zero
 *   FOG_OF_WAR       — vampires opening reveal is skipped
 */
public class VoteManager {

    public enum RoundModifier {
        NONE("Standard Round"),
        DOUBLE_VAMPIRES("Double Vampires"),
        NO_COMPASS("No Compass"),
        SUDDEN_DEATH("Sudden Death Start"),
        FOG_OF_WAR("Fog of War");

        public final String displayName;

        RoundModifier(String displayName) {
            this.displayName = displayName;
        }

        /** Parse a player-supplied string (case-insensitive). */
        public static RoundModifier fromInput(String input) {
            if (input == null) return null;
            String n = input.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
            return switch (n) {
                case "doublevampires", "double" -> DOUBLE_VAMPIRES;
                case "nocompass", "compass"    -> NO_COMPASS;
                case "suddendeath", "sd"       -> SUDDEN_DEATH;
                case "fogofwar", "fog"         -> FOG_OF_WAR;
                default                        -> NONE;
            };
        }
    }

    private static final int VOTE_DURATION_SECONDS = 30;
    private static final String PREFIX = "\u00a78[\u00a76Vote\u00a78] \u00a77";

    private final DraculaVampireHunt plugin;

    /** Votes per spectator UUID — one vote each. */
    private final ConcurrentHashMap<UUID, RoundModifier> votes = new ConcurrentHashMap<>();
    /** Current winning modifier (set after vote closes). */
    private RoundModifier activeModifier = RoundModifier.NONE;
    private boolean voteOpen = false;
    private BukkitTask closeTask;

    public VoteManager(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Start a 30-second voting window. Spectators are notified. */
    public void openVote() {
        if (voteOpen) return;

        votes.clear();
        activeModifier = RoundModifier.NONE;
        voteOpen = true;

        Set<UUID> spectators = plugin.getVampireHuntManager().getSpectatorIds();
        if (spectators.isEmpty()) {
            voteOpen = false;
            return;
        }

        broadcastToSpectators("\u00a76\u00a7lVote for next round modifier! \u00a7e(" + VOTE_DURATION_SECONDS + "s)");
        broadcastToSpectators("\u00a77Options: \u00a7f" + buildOptionList());
        broadcastToSpectators("\u00a77Use: \u00a7f/vhunt vote <option>");

        closeTask = Bukkit.getScheduler().runTaskLater(plugin, this::closeVote, VOTE_DURATION_SECONDS * 20L);
    }

    /**
     * Register a vote from a spectator.
     *
     * @return true if the vote was accepted, false if voting is closed / not a spectator.
     */
    public boolean castVote(UUID playerId, String input) {
        if (!voteOpen) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) p.sendMessage(PREFIX + "\u00a7cNo vote is open right now.");
            return false;
        }

        if (!plugin.getVampireHuntManager().isSpectatorParticipant(playerId)) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) p.sendMessage(PREFIX + "\u00a7cOnly spectators can vote.");
            return false;
        }

        RoundModifier choice = RoundModifier.fromInput(input);
        if (choice == null) return false;
        votes.put(playerId, choice);

        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
            p.sendMessage(PREFIX + "\u00a7aVote cast: \u00a7f" + choice.displayName);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
        return true;
    }

    /** The modifier chosen for the upcoming round (NONE if standard). */
    public RoundModifier getActiveModifier() {
        return activeModifier;
    }

    /** Reset after the modifier has been consumed at round-start. */
    public void reset() {
        if (closeTask != null) { closeTask.cancel(); closeTask = null; }
        votes.clear();
        activeModifier = RoundModifier.NONE;
        voteOpen = false;
    }

    public boolean isVoteOpen() {
        return voteOpen;
    }

    /** Returns a formatted string of voteable options for display. */
    public String getOptionsDisplay() {
        StringBuilder sb = new StringBuilder();
        for (RoundModifier m : RoundModifier.values()) {
            if (sb.length() > 0) sb.append("\u00a77, \u00a7f");
            String key = switch (m) {
                case DOUBLE_VAMPIRES -> "double";
                case NO_COMPASS      -> "nocompass";
                case SUDDEN_DEATH    -> "sd";
                case FOG_OF_WAR      -> "fog";
                case NONE            -> "none";
            };
            sb.append(key).append(" \u00a78(").append(m.displayName).append("\u00a78)\u00a7f");
        }
        return sb.toString();
    }

    /** Returns a formatted vote tally string. */
    public String getTallyDisplay() {
        if (votes.isEmpty()) return "\u00a78no votes yet";
        Map<RoundModifier, Integer> tally = new EnumMap<>(RoundModifier.class);
        for (RoundModifier v : votes.values()) tally.merge(v, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        tally.forEach((mod, count) -> {
            if (sb.length() > 0) sb.append("\u00a77, ");
            sb.append("\u00a7f").append(mod.displayName).append(" \u00a78x").append(count);
        });
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void closeVote() {
        voteOpen = false;
        closeTask = null;

        if (votes.isEmpty()) {
            activeModifier = RoundModifier.NONE;
            broadcastToSpectators("\u00a77No votes cast \u2014 next round will be standard.");
            return;
        }

        // Tally
        Map<RoundModifier, Integer> tally = new EnumMap<>(RoundModifier.class);
        for (RoundModifier v : votes.values()) {
            tally.merge(v, 1, Integer::sum);
        }

        // Winner: most votes; ties broken by enum ordinal (lower = higher priority)
        RoundModifier winner = tally.entrySet().stream()
                .max(Comparator.<Map.Entry<RoundModifier, Integer>>comparingByValue()
                        .thenComparing(e -> -e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .orElse(RoundModifier.NONE);

        activeModifier = winner;

        broadcastToSpectators("\u00a76Vote closed! \u00a7fNext modifier: \u00a7e\u00a7l" + winner.displayName);
        Bukkit.broadcastMessage("\u00a78[\u00a76Vote\u00a78] \u00a77Spectators chose the next modifier: \u00a7e\u00a7l" + winner.displayName);
    }

    private void broadcastToSpectators(String message) {
        for (UUID id : plugin.getVampireHuntManager().getSpectatorIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendMessage(PREFIX + message);
            }
        }
    }

    private String buildOptionList() {
        StringBuilder sb = new StringBuilder();
        for (RoundModifier m : RoundModifier.values()) {
            if (m == RoundModifier.NONE) continue;
            if (sb.length() > 0) sb.append("\u00a77, \u00a7f");
            sb.append(m.name().toLowerCase(Locale.ROOT).replace("_", "-"));
        }
        return sb.toString();
    }
}
