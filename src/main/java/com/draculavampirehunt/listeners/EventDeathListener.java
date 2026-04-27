package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class EventDeathListener implements Listener {

    private final DraculaVampireHunt plugin;

    public EventDeathListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        VampireHuntManager manager = plugin.getVampireHuntManager();

        if (victim == null || !manager.isEventParticipant(victim.getUniqueId())) {
            return;
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);

        Player killer = victim.getKiller();
        manager.handlePlayerDeath(victim, killer);
    }
}