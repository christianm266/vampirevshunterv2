package com.draculavampirehunt.listeners;

import com.draculavampirehunt.DraculaVampireHunt;
import com.draculavampirehunt.managers.VampireHuntManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public class EventCombatListener implements Listener {

    private final DraculaVampireHunt plugin;

    public EventCombatListener(DraculaVampireHunt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        VampireHuntManager manager = plugin.getVampireHuntManager();

        // If no event is running, never interfere with combat — fixes post-match PvP block
        if (!manager.isEventOngoing()) {
            return;
        }

        Player attacker = resolveAttacker(event.getDamager());

        if (attacker == null) {
            return;
        }

        boolean attackerInEvent = manager.isEventParticipant(attacker.getUniqueId());
        boolean victimInEvent = manager.isEventParticipant(victim.getUniqueId());

        if (!attackerInEvent && !victimInEvent) {
            return;
        }

        boolean attackerActive = manager.isActiveParticipant(attacker.getUniqueId());
        boolean victimActive = manager.isActiveParticipant(victim.getUniqueId());

        if (!attackerActive || !victimActive) {
            event.setCancelled(true);
            return;
        }

        boolean sameRoleFriendlyFire = plugin.getConfig().getBoolean("event.friendly-fire.same-role", true);
        if (!sameRoleFriendlyFire && manager.areSameRole(attacker.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        manager.handleCombatHit(event.getDamager(), victim);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }

        return null;
    }
}