package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Grants and displays cosmetic progression titles based on lifetime stats.
 *
 * Titles are checked once per event-end (called from VampireHuntManager#endEvent).
 * The highest applicable title is shown as a title/subtitle screen to the player
 * and broadcast to chat (only on first unlock, stored in EventStatsManager).
 *
 * Current titles (in order of precedence, highest first):
 *   "Apex Predator"  — 50 vampire kills
 *   "Night Stalker"  — 20 vampire kills
 *   "First Blood"    — 1 vampire kill
 *   "Guardian"       — 30 hunter kills
 *   "Vampire Slayer" — 10 hunter kills
 *   "Survivor"       — 10 wins
 *   "Veteran"        — 25 events played
 */
public class TitleManager {

    /** Ordered map: display name → threshold check (evaluated top to bottom; first match wins). */
    public enum ProgressionTitle {
        APEX_PREDATOR    ("Apex Predator",    "§4"),
        NIGHT_STALKER    ("Night Stalker",    "§5"),
        FIRST_BLOOD      ("First Blood",      "§c"),
        GUARDIAN         ("Guardian",         "§b"),
        VAMPIRE_SLAYER   ("Vampire Slayer",   "§e"),
        SURVIVOR         ("Survivor",         "§a"),
        VETERAN          ("Veteran",          "§7");

        public final String name;
        public final String color;

        ProgressionTitle(String name, String color) {
            this.name = name;
            this.color = color;
        }
    }

    private final DraculaVampireHunt plugin;

    public TitleManager(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    /**
     * Evaluate and announce any newly-unlocked progression title for a player.
     * Call this at the end of each round for every participant.
     */
    public void evaluateAndAnnounce(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);

        EventStatsManager stats = plugin.getEventStatsManager();
        ProgressionTitle earned = resolveHighestTitle(playerId, stats);
        if (earned == null) {
            return;
        }

        // Only announce on first unlock
        String key = "title." + earned.name().toLowerCase();
        if (stats.hasUnlockedTitle(playerId, key)) {
            // Already unlocked — still show the tag in chat silently
            if (player != null && player.isOnline()) {
                player.sendMessage("§8[§6Titles§8] §7Your title: " + earned.color + earned.name);
            }
            return;
        }

        stats.unlockTitle(playerId, key);

        String chatLine = "§8[§6Title Unlocked§8] §f" + (player != null ? player.getName() : playerId.toString())
                + " §7earned the title §6" + earned.color + "§l" + earned.name + "§6!";
        Bukkit.broadcastMessage(chatLine);

        if (player != null && player.isOnline()) {
            player.sendTitle(earned.color + "§l" + earned.name, "§7Title unlocked!", 10, 80, 20);
            player.sendMessage("§8[§6Titles§8] §7You unlocked: " + earned.color + "§l" + earned.name);
        }
    }

    /**
     * Returns the highest earned title for a player, or null if none qualify.
     */
    public ProgressionTitle resolveHighestTitle(UUID playerId, EventStatsManager stats) {
        int vampireKills  = stats.getVampireKills(playerId);
        int hunterKills   = stats.getHunterKills(playerId);
        int wins          = stats.getWins(playerId);
        int eventsPlayed  = stats.getEventsPlayed(playerId);

        if (vampireKills >= 50)  return ProgressionTitle.APEX_PREDATOR;
        if (vampireKills >= 20)  return ProgressionTitle.NIGHT_STALKER;
        if (vampireKills >= 1)   return ProgressionTitle.FIRST_BLOOD;
        if (hunterKills  >= 30)  return ProgressionTitle.GUARDIAN;
        if (hunterKills  >= 10)  return ProgressionTitle.VAMPIRE_SLAYER;
        if (wins         >= 10)  return ProgressionTitle.SURVIVOR;
        if (eventsPlayed >= 25)  return ProgressionTitle.VETERAN;

        return null;
    }

    /**
     * Returns the coloured chat-tag prefix for a player, e.g. "§5[Night Stalker] ".
     * Returns an empty string if the player has no title yet.
     */
    public String getChatTag(UUID playerId) {
        EventStatsManager stats = plugin.getEventStatsManager();
        ProgressionTitle title = resolveHighestTitle(playerId, stats);
        if (title == null) {
            return "";
        }
        return title.color + "[" + title.name + "] §r";
    }
}
