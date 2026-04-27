package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EventStatsManager {

    private final DraculaVampireHunt plugin;
    private final ConcurrentHashMap<UUID, PlayerEventStats> cache = new ConcurrentHashMap<>();

    private File statsFile;
    private YamlConfiguration statsConfig;

    public EventStatsManager(DraculaVampireHunt plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        statsFile = new File(plugin.getDataFolder(), "player-stats.yml");
        if (!statsFile.exists()) {
            try {
                statsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create player-stats.yml");
                e.printStackTrace();
            }
        }

        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        cache.clear();

        ConfigurationSection players = statsConfig.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String key : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                PlayerEventStats stats = new PlayerEventStats();
                String base = "players." + key + ".";

                stats.setWins(statsConfig.getInt(base + "wins", 0));
                stats.setLosses(statsConfig.getInt(base + "losses", 0));
                stats.setEventsPlayed(statsConfig.getInt(base + "events-played", 0));
                stats.setVampireKills(statsConfig.getInt(base + "vampire-kills", 0));
                stats.setHunterKills(statsConfig.getInt(base + "hunter-kills", 0));
                stats.setInfections(statsConfig.getInt(base + "infections", 0));
                stats.setVampireWins(statsConfig.getInt(base + "vampire-wins", 0));
                stats.setHunterWins(statsConfig.getInt(base + "hunter-wins", 0));
                stats.setCurrentWinStreak(statsConfig.getInt(base + "current-win-streak", 0));
                stats.setBestWinStreak(statsConfig.getInt(base + "best-win-streak", 0));
                stats.setUnlockedTitles(new ArrayList<>(statsConfig.getStringList(base + "unlocked-titles")));

                cache.put(uuid, stats);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        if (statsConfig == null || statsFile == null) {
            return;
        }

        statsConfig.set("players", null);

        for (UUID uuid : cache.keySet()) {
            PlayerEventStats stats = cache.get(uuid);
            if (stats == null) {
                continue;
            }

            String base = "players." + uuid + ".";
            statsConfig.set(base + "wins", stats.getWins());
            statsConfig.set(base + "losses", stats.getLosses());
            statsConfig.set(base + "events-played", stats.getEventsPlayed());
            statsConfig.set(base + "vampire-kills", stats.getVampireKills());
            statsConfig.set(base + "hunter-kills", stats.getHunterKills());
            statsConfig.set(base + "infections", stats.getInfections());
            statsConfig.set(base + "vampire-wins", stats.getVampireWins());
            statsConfig.set(base + "hunter-wins", stats.getHunterWins());
            statsConfig.set(base + "current-win-streak", stats.getCurrentWinStreak());
            statsConfig.set(base + "best-win-streak", stats.getBestWinStreak());
            statsConfig.set(base + "unlocked-titles", stats.getUnlockedTitles());
        }

        try {
            statsConfig.save(statsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player-stats.yml");
            e.printStackTrace();
        }
    }

    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public PlayerEventStats getStats(UUID playerId) {
        return cache.computeIfAbsent(playerId, uuid -> new PlayerEventStats());
    }

    public double getWinRate(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        int played = stats.getEventsPlayed();
        if (played <= 0) {
            return 0.0D;
        }
        return (double) stats.getWins() / played;
    }

    public List<String> getUnlockedTitles(UUID playerId) {
        return Collections.unmodifiableList(getStats(playerId).getUnlockedTitles());
    }

    public void incrementEventsPlayed(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setEventsPlayed(stats.getEventsPlayed() + 1);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addWin(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setWins(stats.getWins() + 1);
        stats.setCurrentWinStreak(stats.getCurrentWinStreak() + 1);
        if (stats.getCurrentWinStreak() > stats.getBestWinStreak()) {
            stats.setBestWinStreak(stats.getCurrentWinStreak());
        }
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addLoss(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setLosses(stats.getLosses() + 1);
        stats.setCurrentWinStreak(0);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addVampireKill(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setVampireKills(stats.getVampireKills() + 1);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addHunterKill(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setHunterKills(stats.getHunterKills() + 1);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addInfection(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setInfections(stats.getInfections() + 1);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addVampireWin(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setVampireWins(stats.getVampireWins() + 1);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public void addHunterWin(UUID playerId) {
        PlayerEventStats stats = getStats(playerId);
        stats.setHunterWins(stats.getHunterWins() + 1);
        checkUnlocks(playerId, stats);
        saveAsync();
    }

    public String getPrimaryTitle(UUID playerId) {
        List<String> titles = getUnlockedTitles(playerId);
        if (titles.isEmpty()) {
            return "";
        }
        return titles.get(titles.size() - 1);
    }

    private void checkUnlocks(UUID playerId, PlayerEventStats stats) {
        unlockIfEligible(playerId, stats,
                "night-stalker",
                "Night Stalker",
                plugin.getConfig().getBoolean("event.progression.titles.night-stalker.enabled", true),
                stats.getVampireKills() >= plugin.getConfig().getInt("event.progression.titles.night-stalker.required-vampire-kills", 15));

        unlockIfEligible(playerId, stats,
                "first-blood",
                "First Blood",
                plugin.getConfig().getBoolean("event.progression.titles.first-blood.enabled", true),
                stats.getWins() >= plugin.getConfig().getInt("event.progression.titles.first-blood.required-wins", 1));

        unlockIfEligible(playerId, stats,
                "hunter-slayer",
                "Hunter Slayer",
                plugin.getConfig().getBoolean("event.progression.titles.hunter-slayer.enabled", true),
                stats.getHunterKills() >= plugin.getConfig().getInt("event.progression.titles.hunter-slayer.required-hunter-kills", 15));

        unlockIfEligible(playerId, stats,
                "last-survivor",
                "Last Survivor",
                plugin.getConfig().getBoolean("event.progression.titles.last-survivor.enabled", true),
                stats.getBestWinStreak() >= plugin.getConfig().getInt("event.progression.titles.last-survivor.required-best-win-streak", 3));
    }

    private void unlockIfEligible(UUID playerId, PlayerEventStats stats, String key, String displayName, boolean enabled, boolean condition) {
        if (!enabled || !condition) {
            return;
        }

        if (hasUnlocked(stats, key)) {
            return;
        }

        List<String> unlocked = new ArrayList<>(stats.getUnlockedTitles());
        unlocked.add(displayName);
        stats.setUnlockedTitles(unlocked);

        if (Bukkit.getPlayer(playerId) != null && Bukkit.getPlayer(playerId).isOnline()) {
            Bukkit.getPlayer(playerId).sendMessage("§8[§4Vampire Hunt§8] §aUnlocked title: §f" + displayName);
            Bukkit.getPlayer(playerId).sendTitle("§6Title Unlocked", "§f" + displayName, 0, 50, 10);
        }
    }

    private boolean hasUnlocked(PlayerEventStats stats, String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        for (String title : stats.getUnlockedTitles()) {
            String compare = title.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
            if (compare.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static class PlayerEventStats {
        private int wins;
        private int losses;
        private int eventsPlayed;
        private int vampireKills;
        private int hunterKills;
        private int infections;
        private int vampireWins;
        private int hunterWins;
        private int currentWinStreak;
        private int bestWinStreak;
        private List<String> unlockedTitles = new ArrayList<>();

        public int getWins() {
            return wins;
        }

        public void setWins(int wins) {
            this.wins = wins;
        }

        public int getLosses() {
            return losses;
        }

        public void setLosses(int losses) {
            this.losses = losses;
        }

        public int getEventsPlayed() {
            return eventsPlayed;
        }

        public void setEventsPlayed(int eventsPlayed) {
            this.eventsPlayed = eventsPlayed;
        }

        public int getVampireKills() {
            return vampireKills;
        }

        public void setVampireKills(int vampireKills) {
            this.vampireKills = vampireKills;
        }

        public int getHunterKills() {
            return hunterKills;
        }

        public void setHunterKills(int hunterKills) {
            this.hunterKills = hunterKills;
        }

        public int getInfections() {
            return infections;
        }

        public void setInfections(int infections) {
            this.infections = infections;
        }

        public int getVampireWins() {
            return vampireWins;
        }

        public void setVampireWins(int vampireWins) {
            this.vampireWins = vampireWins;
        }

        public int getHunterWins() {
            return hunterWins;
        }

        public void setHunterWins(int hunterWins) {
            this.hunterWins = hunterWins;
        }

        public int getCurrentWinStreak() {
            return currentWinStreak;
        }

        public void setCurrentWinStreak(int currentWinStreak) {
            this.currentWinStreak = currentWinStreak;
        }

        public int getBestWinStreak() {
            return bestWinStreak;
        }

        public void setBestWinStreak(int bestWinStreak) {
            this.bestWinStreak = bestWinStreak;
        }

        public List<String> getUnlockedTitles() {
            return unlockedTitles;
        }

        public void setUnlockedTitles(List<String> unlockedTitles) {
            this.unlockedTitles = unlockedTitles == null ? new ArrayList<>() : unlockedTitles;
        }
    }
}