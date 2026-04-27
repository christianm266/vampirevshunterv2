package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

public class EventRestrictionListener implements Listener {

    private static final String PREFIX = "§8[§4Vampire Hunt§8] §7";

    private final DraculaVampireHunt plugin;
    private final Set<String> alwaysAllowedCommands = new HashSet<>();

    public EventRestrictionListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;

        alwaysAllowedCommands.add("vhunt");
        alwaysAllowedCommands.add("vampirehunt");
        alwaysAllowedCommands.add("bloodhunt");
        alwaysAllowedCommands.add("vh");
        alwaysAllowedCommands.add("help");
        alwaysAllowedCommands.add("?");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!manager.isEventParticipant(player.getUniqueId())) {
            return;
        }

        String raw = event.getMessage();
        if (raw == null || raw.isBlank()) {
            return;
        }

        String normalized = raw.startsWith("/") ? raw.substring(1) : raw;
        String base = normalized.split(" ")[0].toLowerCase();

        if (alwaysAllowedCommands.contains(base)) {
            return;
        }

        if (!manager.shouldBlockCommand(player.getUniqueId(), raw)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(PREFIX + "§cThat command is blocked during the event.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!manager.isEventParticipant(player.getUniqueId())) {
            return;
        }

        if (manager.isProtectedSpectatorTeleport(event)) {
            return;
        }

        if (manager.isSpectatorParticipant(player.getUniqueId())) {
            boolean blockEscape = plugin.getConfig().getBoolean("event.spectator.block-teleport-escape", true);
            if (blockEscape) {
                event.setCancelled(true);
                player.sendMessage(PREFIX + "§cSpectators cannot teleport out during the event.");
            }
            return;
        }

        if (manager.isActiveParticipant(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(PREFIX + "§cYou cannot teleport during the event.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!manager.isActiveParticipant(player.getUniqueId())) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        Material type = item.getType();

        if (plugin.getConfig().getBoolean("event.disable-chorus-fruit", true) && type == Material.CHORUS_FRUIT) {
            event.setCancelled(true);
            player.sendMessage(PREFIX + "§cChorus fruit is disabled during the event.");
            return;
        }

        if (plugin.getConfig().getBoolean("event.disable-elytra", true) && type == Material.ELYTRA) {
            event.setCancelled(true);
            player.sendMessage(PREFIX + "§cElytra is disabled during the event.");
            return;
        }

        if (plugin.getConfig().getBoolean("event.disable-tridents", true) && type == Material.TRIDENT) {
            event.setCancelled(true);
            player.sendMessage(PREFIX + "§cTridents are disabled during the event.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (!manager.isEventParticipant(player.getUniqueId())) {
            return;
        }

        if (!manager.isActiveParticipant(player.getUniqueId()) && !manager.isSpectatorParticipant(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(PREFIX + "§cYou cannot drop items during the event.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        VampireHuntManager manager = plugin.getVampireHuntManager();
        if (!manager.isActiveParticipant(player.getUniqueId()) && !manager.isSpectatorParticipant(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        VampireHuntManager manager = plugin.getVampireHuntManager();
        if (!manager.isActiveParticipant(player.getUniqueId()) && !manager.isSpectatorParticipant(player.getUniqueId())) {
            return;
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        VampireHuntManager manager = plugin.getVampireHuntManager();
        if (!manager.isActiveParticipant(player.getUniqueId())) {
            return;
        }

        if (plugin.getConfig().getBoolean("event.disable-natural-regen", true)
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }
}