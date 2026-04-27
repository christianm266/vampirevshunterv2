package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class EventArenaManager {

    private final DraculaVampireHunt plugin;

    public EventArenaManager(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    public boolean hasLobbySpawn() {
        return getLobbySpawn() != null;
    }

    public boolean hasArenaReady() {
        return getVampireSpawn() != null && getHunterSpawn() != null && getLobbySpawn() != null && getReturnSpawn() != null;
    }

    public Location getLobbySpawn() {
        return getLocation("arena.lobby");
    }

    public Location getReturnSpawn() {
        return getLocation("arena.return");
    }

    public Location getVampireSpawn() {
        return getLocation("arena.vampire");
    }

    public Location getHunterSpawn() {
        return getLocation("arena.hunter");
    }

    public Location getSpectatorSpawn() {
        Location spectator = getLocation("arena.spectator");
        if (spectator != null) {
            return spectator;
        }

        Location fallback = getHunterSpawn();
        if (fallback == null) {
            return null;
        }

        return fallback.clone().add(0.0D, 15.0D, 0.0D);
    }

    public boolean teleportToLobby(Player player) {
        return teleport(player, getLobbySpawn());
    }

    public boolean teleportToReturn(Player player) {
        return teleport(player, getReturnSpawn());
    }

    public boolean teleportToVampireSpawn(Player player) {
        return teleport(player, getVampireSpawn());
    }

    public boolean teleportToHunterSpawn(Player player) {
        return teleport(player, getHunterSpawn());
    }

    public boolean teleportToSpectatorSpawn(Player player) {
        return teleport(player, getSpectatorSpawn());
    }

    public Location getLocation(String path) {
        String worldName = plugin.getConfig().getString(path + ".world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found for location path: " + path + " (" + worldName + ")");
            return null;
        }

        double x = plugin.getConfig().getDouble(path + ".x");
        double y = plugin.getConfig().getDouble(path + ".y");
        double z = plugin.getConfig().getDouble(path + ".z");
        float yaw = (float) plugin.getConfig().getDouble(path + ".yaw", 0.0D);
        float pitch = (float) plugin.getConfig().getDouble(path + ".pitch", 0.0D);

        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setLocation(String path, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.getConfig().set(path + ".pitch", location.getPitch());
        plugin.saveConfig();
    }

    public boolean teleport(Player player, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) {
            return false;
        }

        return player.teleport(location);
    }
}