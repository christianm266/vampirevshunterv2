package com.draculavampirehunt.managers;

import com.draculavampirehunt.DraculaVampireHunt;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VampireHuntManager {

    public enum CommandSource { PLAYER, CONSOLE }

    private static final String PREFIX = "§8[§4Vampire Hunt§8] §7";

    private final DraculaVampireHunt plugin;
    private final VoteManager voteManager;

    private final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();

    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vampires = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hunters = ConcurrentHashMap.newKeySet();

    private final Set<UUID> spectatorPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> payoutEligibleSpectators = ConcurrentHashMap.newKeySet();
    private final Set<UUID> payoutEligibleActivePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> protectedTeleportPlayers = ConcurrentHashMap.newKeySet();

    private final Set<UUID> originalVampires = ConcurrentHashMap.newKeySet();
    private final Set<UUID> originalHunters = ConcurrentHashMap.newKeySet();

    private final Set<UUID> disconnectedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingReturnTeleport = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<UUID, PlayerSnapshot> savedStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BukkitTask> disconnectTimeoutTasks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, RoleClass> selectedClasses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> classAbilityCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> hunterCampReferenceMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> hunterCampReferenceLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> hunterCampStage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> hunterCampRevealCooldownUntil = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Integer> roundKills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> roundInfections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> roundAliveStart = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> eliminationTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> closestEscapeMeters = new ConcurrentHashMap<>();

    private BossBar eventBossBar;
    private BukkitTask countdownTask;
    private BukkitTask timerTask;
    private BukkitTask trackerTask;
    private BukkitTask ambianceTask;
    private BukkitTask openingRevealEndTask;

    private EventPhase phase = EventPhase.IDLE;
    private long eventEndMillis = 0L;
    private long configuredDurationSeconds = 0L;
    private long eventStartMillis = 0L;
    private long openingRevealEndsAt = 0L;
    private int readyCountdownSecondsLeft = -1;
    private long suddenDeathStartMillis = 0L;

    public VampireHuntManager(DraculaVampireHunt plugin) {
        this.plugin = plugin;
        this.voteManager = new VoteManager(plugin);
    }

    public VoteManager getVoteManager() {
        return voteManager;
    }

    public boolean joinEvent(Player player) {
        if (player == null || !player.isOnline()) return false;

        UUID playerId = player.getUniqueId();

        if (queuedPlayers.contains(playerId) || activePlayers.contains(playerId) || spectatorPlayers.contains(playerId)) {
            plugin.getChatManager().sendPrefixed(player, "§eYou are already in the event.");
            return false;
        }

        if (phase == EventPhase.ACTIVE || phase == EventPhase.ENDING || phase == EventPhase.SUDDEN_DEATH) {
            plugin.getChatManager().sendPrefixed(player, "§cAn event is already running.");
            return false;
        }

        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        int maxPlayers = Math.max(minPlayers, plugin.getConfig().getInt("event.max-players", 20));

        if (queuedPlayers.size() >= maxPlayers) {
            plugin.getChatManager().sendPrefixed(player, "§cThe event queue is full.");
            return false;
        }

        if (!plugin.getEventArenaManager().hasLobbySpawn()) {
            plugin.getChatManager().sendPrefixed(player, "§cThe event lobby is not configured.");
            return false;
        }

        queuedPlayers.add(playerId);
        readyPlayers.remove(playerId);
        selectedClasses.putIfAbsent(playerId, RoleClass.NONE);

        plugin.getEventArenaManager().teleportToLobby(player);
        plugin.getChatManager().sendPrefixed(player, "§aYou joined the Vampire Hunt queue.");
        plugin.getChatManager().sendPrefixed(player, "§7Use §f/vhunt ready §7when you are ready.");
        plugin.getChatManager().sendPrefixed(player, "§7Use §f/vhunt class <name> §7to preselect your class.");

        if (phase == EventPhase.IDLE) phase = EventPhase.QUEUEING;

        if (queuedPlayers.size() >= minPlayers) beginReadyCheckIfPossible();

        return true;
    }

    public boolean leaveEvent(Player player, boolean restoreState) {
        if (player == null) return false;

        UUID playerId = player.getUniqueId();

        if (queuedPlayers.remove(playerId)) {
            readyPlayers.remove(playerId);
            selectedClasses.remove(playerId);
            plugin.getChatManager().sendPrefixed(player, "§eYou left the event queue.");
            if (restoreState) teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            checkCountdownViability();
            if (queuedPlayers.isEmpty() && (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK || phase == EventPhase.COUNTDOWN))
                phase = EventPhase.IDLE;
            return true;
        }

        if (spectatorPlayers.contains(playerId)) {
            markSpectatorPayoutIneligible(playerId);
            spectatorPlayers.remove(playerId);
            disconnectedPlayers.remove(playerId);
            cancelDisconnectTask(playerId);
            if (eventBossBar != null) eventBossBar.removePlayer(player);
            if (restoreState) {
                restorePlayer(player);
                teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            }
            plugin.getChatManager().sendPrefixed(player, "§eYou left spectator mode and gave up payout eligibility.");
            return true;
        }

        if (!activePlayers.contains(playerId) && !disconnectedPlayers.contains(playerId)) return false;

        eliminatePlayer(playerId, true, true, "§cYou left the event.");
        checkWinConditions();
        return true;
    }

    public boolean setPlayerReady(Player player) {
        if (player == null || !queuedPlayers.contains(player.getUniqueId())) return false;

        UUID playerId = player.getUniqueId();
        if (readyPlayers.contains(playerId)) {
            plugin.getChatManager().sendPrefixed(player, "§eYou are already marked ready.");
            return false;
        }

        readyPlayers.add(playerId);
        plugin.getChatManager().sendPrefixed(player, "§aYou are now ready.");

        if (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK) beginReadyCheckIfPossible();

        if (hasMinimumReadyPlayers() && phase == EventPhase.READY_CHECK) {
            plugin.getChatManager().broadcastToParticipants("§aMinimum ready players reached. Starting countdown.");
            startCountdown();
        }

        return true;
    }

    public boolean unreadyPlayer(Player player) {
        if (player == null || !queuedPlayers.contains(player.getUniqueId())) return false;

        UUID playerId = player.getUniqueId();
        if (!readyPlayers.remove(playerId)) {
            plugin.getChatManager().sendPrefixed(player, "§eYou were not marked ready.");
            return false;
        }

        plugin.getChatManager().sendPrefixed(player, "§eYou are no longer ready.");
        if (phase == EventPhase.COUNTDOWN && !hasMinimumReadyPlayers()) {
            cancelCountdown();
            phase = EventPhase.READY_CHECK;
            readyCountdownSecondsLeft = Math.max(10, plugin.getConfig().getInt("event.ready-check.timeout-seconds", 25));
            plugin.getChatManager().broadcastToParticipants("§cCountdown paused because ready players dropped below the minimum.");
            beginReadyCheckIfPossible();
        }
        return true;
    }

    public boolean startEventByAdmin(CommandSource source) {
        if (phase == EventPhase.ACTIVE || phase == EventPhase.COUNTDOWN || phase == EventPhase.SUDDEN_DEATH) {
            return false;
        }

        if (queuedPlayers.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (!activePlayers.contains(id) && !spectatorPlayers.contains(id)) {
                    queuedPlayers.add(id);
                    selectedClasses.putIfAbsent(id, RoleClass.NONE);
                }
            }
        }

        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) return false;

        readyPlayers.addAll(queuedPlayers);

        if (phase == EventPhase.IDLE || phase == EventPhase.QUEUEING) phase = EventPhase.READY_CHECK;

        startCountdown(3);
        return true;
    }

    public String adminForceVampire(Player target) {
        if (target == null) return "§cTarget player is null.";

        UUID id = target.getUniqueId();

        if (!activePlayers.contains(id)) return "§cPlayer §f" + target.getName() + " §cis not an active participant.";
        if (vampires.contains(id)) return "§ePlayer §f" + target.getName() + " §eis already a vampire.";

        hunters.remove(id);
        vampires.add(id);
        hunterCampReferenceLocations.remove(id);
        hunterCampReferenceMillis.remove(id);
        hunterCampStage.remove(id);
        hunterCampRevealCooldownUntil.remove(id);

        selectedClasses.put(id, RoleClass.VAMPIRE_STALKER);

        preparePlayerForEvent(target, true);
        teleportProtected(target, plugin.getEventArenaManager().getVampireSpawn());
        giveRoleKit(target);
        applyStartInvisibility(target);
        applyPermanentVampireVision(target);

        target.sendTitle("§5VAMPIRE", "§7Admin assigned role", 0, 60, 10);
        plugin.getChatManager().sendPrefixed(target, "§5An admin has set your role to §5Vampire§7.");

        plugin.getChatManager().broadcastToParticipants("§5" + target.getName() + " §7has been switched to the §5Vampire §7team by an admin.");
        checkWinConditions();
        return "§aForced §f" + target.getName() + " §ato vampire team.";
    }

    public String adminForceHunter(Player target) {
        if (target == null) return "§cTarget player is null.";

        UUID id = target.getUniqueId();

        if (!activePlayers.contains(id)) return "§cPlayer §f" + target.getName() + " §cis not an active participant.";
        if (hunters.contains(id)) return "§ePlayer §f" + target.getName() + " §eis already a hunter.";

        vampires.remove(id);
        hunters.add(id);

        selectedClasses.put(id, RoleClass.HUNTER_TRACKER);

        preparePlayerForEvent(target, true);
        teleportProtected(target, plugin.getEventArenaManager().getHunterSpawn());
        giveRoleKit(target);
        giveTracker(target);

        hunterCampReferenceLocations.put(id, target.getLocation().clone());
        hunterCampReferenceMillis.put(id, System.currentTimeMillis());
        hunterCampStage.put(id, 0);

        target.sendTitle("§bHUNTER", "§7Admin assigned role", 0, 60, 10);
        plugin.getChatManager().sendPrefixed(target, "§bAn admin has set your role to §bHunter§7.");

        plugin.getChatManager().broadcastToParticipants("§b" + target.getName() + " §7has been switched to the §bHunter §7team by an admin.");
        checkWinConditions();
        return "§aForced §f" + target.getName() + " §ato hunter team.";
    }

    public String getAdminPlayerListMessage() {
        StringBuilder sb = new StringBuilder();

        sb.append("\n§5§lVampires (").append(vampires.size()).append(")§r");
        if (vampires.isEmpty()) {
            sb.append("\n  §8None");
        } else {
            for (UUID id : vampires) {
                String name = resolveName(id);
                boolean online = Bukkit.getPlayer(id) != null;
                sb.append("\n  §5").append(name).append(online ? " §a[online]" : " §c[offline]");
            }
        }

        sb.append("\n§b§lHunters (").append(hunters.size()).append(")§r");
        if (hunters.isEmpty()) {
            sb.append("\n  §8None");
        } else {
            for (UUID id : hunters) {
                String name = resolveName(id);
                boolean online = Bukkit.getPlayer(id) != null;
                sb.append("\n  §b").append(name).append(online ? " §a[online]" : " §c[offline]");
            }
        }

        sb.append("\n§7§lSpectators (").append(spectatorPlayers.size()).append(")§r");
        if (spectatorPlayers.isEmpty()) {
            sb.append("\n  §8None");
        } else {
            for (UUID id : spectatorPlayers) {
                String name = resolveName(id);
                boolean online = Bukkit.getPlayer(id) != null;
                sb.append("\n  §7").append(name).append(online ? " §a[online]" : " §c[offline]");
            }
        }

        if (!queuedPlayers.isEmpty()) {
            sb.append("\n§e§lQueued (").append(queuedPlayers.size()).append(")§r");
            for (UUID id : queuedPlayers) {
                String name = resolveName(id);
                boolean ready = readyPlayers.contains(id);
                sb.append("\n  §e").append(name).append(ready ? " §a[ready]" : " §c[not ready]");
            }
        }

        return sb.toString();
    }

    public boolean stopEventGracefully() {
        if (!isEventOngoing() && queuedPlayers.isEmpty()) return false;
        endEvent(EventWinner.NONE, true);
        return true;
    }

    public boolean forceStopEvent() {
        if (!isEventOngoing() && queuedPlayers.isEmpty()) return false;
        endEvent(EventWinner.NONE, true);
        return true;
    }

    public void shutdown() {
        endEvent(EventWinner.NONE, true);
    }

    public void handlePlayerJoin(Player player) {
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();

        if (pendingReturnTeleport.remove(playerId)) {
            restorePlayer(player);
            teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            plugin.getChatManager().sendPrefixed(player, "§eYou were restored after leaving the event.");
            return;
        }

        if (spectatorPlayers.contains(playerId)) {
            preparePlayerForEvent(player, true);
            player.setGameMode(GameMode.SPECTATOR);
            player.setAllowFlight(true);
            player.setFlying(true);
            teleportProtected(player, plugin.getEventArenaManager().getSpectatorSpawn());
            if (eventBossBar != null) eventBossBar.addPlayer(player);
            plugin.getChatManager().sendPrefixed(player, "§7You rejoined as an event spectator.");
            return;
        }

        if (!disconnectedPlayers.contains(playerId)) return;

        disconnectedPlayers.remove(playerId);
        cancelDisconnectTask(playerId);

        if (!activePlayers.contains(playerId)) {
            restorePlayer(player);
            teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            return;
        }

        preparePlayerForEvent(player, true);

        if (isVampire(playerId)) {
            teleportProtected(player, plugin.getEventArenaManager().getVampireSpawn());
            giveRoleKit(player);
            reapplyCurrentVampireInvisibility(player);
            plugin.getChatManager().sendPrefixed(player, "§5You rejoined as a vampire.");
        } else if (isHunter(playerId)) {
            teleportProtected(player, plugin.getEventArenaManager().getHunterSpawn());
            giveRoleKit(player);
            giveTracker(player);
            plugin.getChatManager().sendPrefixed(player, "§bYou rejoined as a hunter.");
        }

        if (eventBossBar != null) eventBossBar.addPlayer(player);
    }

    public void handlePlayerQuit(Player player) {
        if (player == null) return;

        UUID playerId = player.getUniqueId();

        if (queuedPlayers.remove(playerId)) {
            readyPlayers.remove(playerId);
            selectedClasses.remove(playerId);
            checkCountdownViability();
            if (queuedPlayers.isEmpty() && (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK))
                phase = EventPhase.IDLE;
            return;
        }

        if (spectatorPlayers.contains(playerId)) {
            disconnectedPlayers.add(playerId);
            return;
        }

        if (!activePlayers.contains(playerId)) return;

        boolean graceEnabled = plugin.getConfig().getBoolean("event.disconnect.grace-enabled", true);
        int graceSeconds = Math.max(1, plugin.getConfig().getInt("event.disconnect.grace-seconds", 20));
        boolean eliminateIfNotBack = plugin.getConfig().getBoolean("event.disconnect.eliminate-if-not-back", true);

        if (!graceEnabled) {
            eliminateDisconnectedPlayerImmediately(player);
            return;
        }

        disconnectedPlayers.add(playerId);
        if (eventBossBar != null) eventBossBar.removePlayer(player);
        cancelDisconnectTask(playerId);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            disconnectTimeoutTasks.remove(playerId);
            if (!disconnectedPlayers.contains(playerId)) return;
            disconnectedPlayers.remove(playerId);

            if (!eliminateIfNotBack) {
                sendToOfflineSpectator(playerId);
                checkWinConditions();
                return;
            }

            eliminateOfflinePlayer(playerId, true, "§cYou were eliminated for disconnecting during the event.");
            checkWinConditions();
        }, graceSeconds * 20L);

        disconnectTimeoutTasks.put(playerId, task);
    }

    public void handlePlayerDeath(Player victim, Player killer) {
        if (victim == null) return;

        UUID victimId = victim.getUniqueId();
        if (!activePlayers.contains(victimId)) return;

        eliminationTime.putIfAbsent(victimId, System.currentTimeMillis());

        if (isHunter(victimId) && killer != null && isVampire(killer.getUniqueId())) {
            infectHunter(victim, killer);
            checkWinConditions();
            return;
        }

        if (killer != null && activePlayers.contains(killer.getUniqueId())) {
            recordKill(killer, victim);
            playKillEffects(killer, victim, false);
        }

        eliminatePlayer(victimId, true, false, isVampire(victimId)
                ? "§cYou were slain and removed from combat."
                : "§cYou were eliminated from the event.");

        checkWinConditions();
    }

    public void handleCombatHit(Entity damagerEntity, Player victim) {
        if (damagerEntity == null || victim == null) return;
        if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;

        Player attacker = resolveAttackingPlayer(damagerEntity);
        if (attacker == null) return;

        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();

        if (!activePlayers.contains(attackerId) || !activePlayers.contains(victimId)) return;

        if (isVampire(attackerId) && isHunter(victimId)) {
            applyVampireAttackEffects(attacker, victim);
            applyCloseHeartbeat(victim, attacker);
        }

        if (isHunter(attackerId) && isVampire(victimId)) {
            if (getSelectedClass(attackerId) == RoleClass.HUNTER_PRIEST) {
                revealNearbyVampires(attacker, 20.0D, 3);
            }
        }
    }

    public String getRoleTag(UUID playerId) {
        if (vampires.contains(playerId)) return "§8[§5Vampire§8] ";
        if (hunters.contains(playerId)) return "§8[§bHunter§8] ";
        if (spectatorPlayers.contains(playerId)) return "§8[§7Spectator§8] ";
        return "";
    }

    private Player resolveAttackingPlayer(Entity damagerEntity) {
        if (damagerEntity instanceof Player player) return player;
        if (damagerEntity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private void applyVampireAttackEffects(Player attacker, Player victim) {
        if (attacker == null || victim == null || !attacker.isOnline() || !victim.isOnline()) return;
        applyVampireDizziness(attacker, victim);
        applyHunterDarknessPulse(victim);
    }

    private void applyVampireDizziness(Player attacker, Player victim) {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.enabled", true);
        if (!enabled) return;

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.attack-effects.dizziness.seconds", 2));
        int amplifier = Math.max(0, plugin.getConfig().getInt("event.vampire.attack-effects.dizziness.amplifier", 0));
        boolean ambient = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.ambient", false);
        boolean particles = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.particles", false);
        boolean icon = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.icon", true);
        double chancePercent = Math.max(0.0D, Math.min(100.0D, plugin.getConfig().getDouble("event.vampire.attack-effects.dizziness.chance-percent", 100.0D)));

        if (chancePercent < 100.0D && Math.random() * 100.0D > chancePercent) return;

        victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, seconds * 20, amplifier, ambient, particles, icon));

        if (plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.notify-hunter", true))
            victim.sendMessage(PREFIX + "§5A vampire attack made you dizzy for §f" + seconds + "§5 seconds.");

        if (plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.notify-vampire", false))
            attacker.sendMessage(PREFIX + "§dYou inflicted dizziness on §f" + victim.getName() + "§d.");
    }

    private void applyHunterDarknessPulse(Player hunter) {
        boolean enabled = plugin.getConfig().getBoolean("event.horror.darkness-pulses.enabled", true);
        if (!enabled || hunter == null || !hunter.isOnline() || !isHunter(hunter.getUniqueId())) return;

        int darknessSeconds = Math.max(1, plugin.getConfig().getInt("event.horror.darkness-pulses.seconds", 2));
        int darknessAmplifier = Math.max(0, plugin.getConfig().getInt("event.horror.darkness-pulses.amplifier", 0));
        int slownessSeconds = Math.max(0, plugin.getConfig().getInt("event.horror.darkness-pulses.slowness-seconds", 1));

        hunter.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darknessSeconds * 20, darknessAmplifier, false, false, false));
        if (slownessSeconds > 0)
            hunter.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slownessSeconds * 20, 0, false, false, false));

        hunter.playSound(hunter.getLocation(), Sound.AMBIENT_CAVE, 0.6f, 0.8f);
        hunter.sendTitle("", "§8The darkness closes in...", 0, 20, 5);
    }

    private void applyCloseHeartbeat(Player hunter, Player vampire) {
        if (hunter == null || vampire == null || !hunter.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.horror.close-heartbeat.enabled", true);
        if (!enabled) return;
        double threshold = plugin.getConfig().getDouble("event.horror.close-heartbeat.radius", 10.0D);
        if (hunter.getLocation().distanceSquared(vampire.getLocation()) > threshold * threshold) return;
        hunter.playSound(hunter.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 0.6f);
    }

    public boolean isEventOngoing() {
        return phase == EventPhase.READY_CHECK
                || phase == EventPhase.COUNTDOWN
                || phase == EventPhase.ACTIVE
                || phase == EventPhase.SUDDEN_DEATH
                || phase == EventPhase.ENDING;
    }

    public boolean isEventParticipant(UUID playerId) {
        return queuedPlayers.contains(playerId)
                || activePlayers.contains(playerId)
                || spectatorPlayers.contains(playerId)
                || disconnectedPlayers.contains(playerId);
    }

    public boolean isActiveParticipant(UUID playerId) { return activePlayers.contains(playerId); }
    public boolean isVampire(UUID playerId) { return vampires.contains(playerId); }
    public boolean isHunter(UUID playerId) { return hunters.contains(playerId); }
    public boolean isSpectatorParticipant(UUID playerId) { return spectatorPlayers.contains(playerId); }
    public boolean isSpectatorPayoutEligible(UUID playerId) {
        return payoutEligibleSpectators.contains(playerId) || payoutEligibleActivePlayers.contains(playerId);
    }

    public void markSpectatorPayoutIneligible(UUID playerId) {
        payoutEligibleSpectators.remove(playerId);
        payoutEligibleActivePlayers.remove(playerId);
    }

    public boolean isProtectedSpectatorTeleport(PlayerTeleportEvent event) {
        if (event == null || event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) return false;
        return protectedTeleportPlayers.remove(event.getPlayer().getUniqueId());
    }

    public Set<UUID> getAllParticipantIds() {
        Set<UUID> ids = new HashSet<>();
        ids.addAll(queuedPlayers);
        ids.addAll(activePlayers);
        ids.addAll(disconnectedPlayers);
        ids.addAll(spectatorPlayers);
        return Collections.unmodifiableSet(ids);
    }

    public Set<UUID> getSpectatorIds() { return Collections.unmodifiableSet(spectatorPlayers); }
    public String getPhaseName() { return phase.name().toLowerCase(Locale.ROOT); }
    public int getQueuedCount() { return queuedPlayers.size(); }
    public int getReadyCount() { return readyPlayers.size(); }
    public int getActiveCount() { return activePlayers.size(); }
    public int getVampireCount() { return vampires.size(); }
    public int getHunterCount() { return hunters.size(); }

    public RoleClass getSelectedClass(UUID playerId) {
        return selectedClasses.getOrDefault(playerId, RoleClass.NONE);
    }

    public boolean selectClass(Player player, String input) {
        if (player == null || input == null || input.isBlank()) return false;

        UUID playerId = player.getUniqueId();
        if (!queuedPlayers.contains(playerId) && !activePlayers.contains(playerId)) {
            plugin.getChatManager().sendPrefixed(player, "§cYou must be in the event queue or round to select a class.");
            return false;
        }

        RoleClass roleClass = RoleClass.fromInput(input);
        if (roleClass == null) {
            plugin.getChatManager().sendPrefixed(player, "§cUnknown class. Use: stalker, brute, tracker, priest.");
            return false;
        }

        selectedClasses.put(playerId, roleClass);
        plugin.getChatManager().sendPrefixed(player, "§aSelected class: §f" + roleClass.getDisplayName());
        return true;
    }

    public boolean useClassAbility(Player player) {
        if (player == null || !activePlayers.contains(player.getUniqueId())) return false;

        UUID playerId = player.getUniqueId();
        RoleClass roleClass = getSelectedClass(playerId);
        if (roleClass == RoleClass.NONE) {
            plugin.getChatManager().sendPrefixed(player, "§cYou do not have a class selected.");
            return false;
        }

        long now = System.currentTimeMillis();
        long cooldownUntil = classAbilityCooldowns.getOrDefault(playerId, 0L);
        if (now < cooldownUntil) {
            long seconds = Math.max(1L, (cooldownUntil - now) / 1000L);
            plugin.getChatManager().sendPrefixed(player, "§eAbility on cooldown for §f" + seconds + "§e more seconds.");
            return false;
        }

        switch (roleClass) {
            case HUNTER_PRIEST -> {
                if (!isHunter(playerId)) { plugin.getChatManager().sendPrefixed(player, "§cOnly hunters can use this class ability."); return false; }
                revealNearbyVampires(player, 28.0D, 4);
                player.sendTitle("§bHoly Reveal", "§7Nearby vampires exposed", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 35000L);
                return true;
            }
            case HUNTER_TRACKER -> {
                if (!isHunter(playerId)) { plugin.getChatManager().sendPrefixed(player, "§cOnly hunters can use this class ability."); return false; }
                Player nearest = findNearestVisibleVampire(player, 80.0D);
                if (nearest == null) { plugin.getChatManager().sendPrefixed(player, "§eNo vampire detected."); classAbilityCooldowns.put(playerId, now + 10000L); return true; }
                player.setCompassTarget(nearest.getLocation());
                player.sendTitle("§bTracker Pulse", "§f" + nearest.getName() + " located", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 15000L);
                return true;
            }
            case VAMPIRE_STALKER -> {
                if (!isVampire(playerId)) { plugin.getChatManager().sendPrefixed(player, "§cOnly vampires can use this class ability."); return false; }
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 10 * 20, 0, false, false, false));
                player.sendTitle("§5Stalker Veil", "§7You vanish into the dark", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 30000L);
                return true;
            }
            case VAMPIRE_BRUTE -> {
                if (!isVampire(playerId)) { plugin.getChatManager().sendPrefixed(player, "§cOnly vampires can use this class ability."); return false; }
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 8 * 20, 0, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 8 * 20, 0, false, false, true));
                player.sendTitle("§4Brute Rage", "§7Power over speed", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 40000L);
                return true;
            }
            default -> { return false; }
        }
    }

    public Player getNextSpectatorTarget(Player spectator, boolean vampiresOnly, boolean huntersOnly) {
        if (spectator == null || !spectatorPlayers.contains(spectator.getUniqueId())) return null;

        List<Player> candidates = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            if (vampiresOnly && !vampires.contains(uuid)) continue;
            if (huntersOnly && !hunters.contains(uuid)) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) candidates.add(player);
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        Entity current = spectator.getSpectatorTarget();
        if (current instanceof Player currentPlayer) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getUniqueId().equals(currentPlayer.getUniqueId()))
                    return candidates.get((i + 1) % candidates.size());
            }
        }
        return candidates.get(0);
    }

    public String getTeamCountLine() {
        return "§7Hunters: §b" + getHunterCount() + " §8| §7Vampires: §5" + getVampireCount() + " §8| §7Spectators: §f" + spectatorPlayers.size();
    }

    public boolean shouldBlockCommand(UUID playerId, String rawCommand) {
        if (!activePlayers.contains(playerId) && !spectatorPlayers.contains(playerId) && !queuedPlayers.contains(playerId))
            return false;

        List<String> blocked = plugin.getConfig().getStringList("event.blocked-commands");
        String normalized = rawCommand == null ? "" : rawCommand.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        String base = normalized.split(" ")[0];
        for (String cmd : blocked) {
            if (base.equalsIgnoreCase(cmd)) return true;
        }
        return false;
    }

    public boolean areSameRole(UUID first, UUID second) {
        return (vampires.contains(first) && vampires.contains(second))
                || (hunters.contains(first) && hunters.contains(second));
    }

    public void giveTracker(Player player) {
        if (player == null || !player.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.hunter-tracker.enabled", true);
        if (!enabled || !isHunter(player.getUniqueId())) return;

        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bVampire Tracker");
            List<String> lore = new ArrayList<>();
            lore.add("§7Tracks the nearest vampire.");
            if (getSelectedClass(player.getUniqueId()) == RoleClass.HUNTER_TRACKER) lore.add("§bTracker Class: enhanced tracking.");
            meta.setLore(lore);
            compass.setItemMeta(meta);
        }

        PlayerInventory inv = player.getInventory();
        if (!inv.contains(Material.COMPASS)) inv.addItem(compass);
    }

    private void beginReadyCheckIfPossible() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) return;

        boolean enabled = plugin.getConfig().getBoolean("event.ready-check.enabled", true);
        if (!enabled) { startCountdown(); return; }

        if (phase != EventPhase.READY_CHECK && phase != EventPhase.COUNTDOWN) {
            phase = EventPhase.READY_CHECK;
            readyCountdownSecondsLeft = Math.max(10, plugin.getConfig().getInt("event.ready-check.timeout-seconds", 25));
        }

        if (countdownTask != null && phase == EventPhase.COUNTDOWN) return;

        if (phase == EventPhase.READY_CHECK && countdownTask == null) {
            countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                int min = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
                if (queuedPlayers.size() < min) {
                    cancelCountdown();
                    phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
                    return;
                }

                if (hasMinimumReadyPlayers()) { cancelCountdown(); startCountdown(); return; }

                if (readyCountdownSecondsLeft == 25 || readyCountdownSecondsLeft % 5 == 0 || readyCountdownSecondsLeft <= 5) {
                    for (UUID uuid : queuedPlayers) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            player.sendTitle("§6Ready Check", "§fUse /vhunt ready §7(" + readyPlayers.size() + "/" + queuedPlayers.size() + ")", 0, 20, 5);
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
                        }
                    }
                    plugin.getChatManager().broadcastToParticipants("§6Ready Check: §f" + readyPlayers.size() + "/" + queuedPlayers.size() + " ready.");
                }

                if (readyCountdownSecondsLeft <= 0) {
                    cancelCountdown();
                    if (readyPlayers.size() < min) {
                        phase = EventPhase.READY_CHECK;
                        plugin.getChatManager().broadcastToParticipants("§cReady check failed. Not enough ready players.");
                        return;
                    }
                    startCountdown();
                    return;
                }

                readyCountdownSecondsLeft--;
            }, 0L, 20L);
        }
    }

    private boolean allQueuedPlayersReady() {
        return !queuedPlayers.isEmpty() && readyPlayers.containsAll(queuedPlayers);
    }

    private boolean hasMinimumReadyPlayers() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        return readyPlayers.size() >= minPlayers;
    }

    private void startCountdown() {
        int seconds = Math.max(5, plugin.getConfig().getInt("event.countdown-seconds", 15));
        startCountdown(seconds);
    }

    private void startCountdown(int seconds) {
        cancelCountdown();
        if (!plugin.getEventArenaManager().hasArenaReady()) { phase = EventPhase.QUEUEING; return; }

        phase = EventPhase.COUNTDOWN;
        final int[] timeLeft = {seconds};

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));

            if (queuedPlayers.size() < minPlayers) {
                plugin.getChatManager().broadcastToParticipants("§cCountdown cancelled: not enough players.");
                cancelCountdown();
                phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
                return;
            }

            if (plugin.getConfig().getBoolean("event.ready-check.enabled", true) && !hasMinimumReadyPlayers()) {
                plugin.getChatManager().broadcastToParticipants("§cCountdown paused: ready players dropped below the minimum.");
                cancelCountdown();
                phase = EventPhase.READY_CHECK;
                readyCountdownSecondsLeft = Math.max(10, plugin.getConfig().getInt("event.ready-check.timeout-seconds", 25));
                beginReadyCheckIfPossible();
                return;
            }

            if (timeLeft[0] <= 0) { cancelCountdown(); startEvent(); return; }

            for (UUID uuid : queuedPlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.sendTitle("§4Vampire Hunt", "§7Starting in §f" + timeLeft[0], 0, 20, 5);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                }
            }

            plugin.getChatManager().broadcastToParticipants("§eEvent starts in §f" + timeLeft[0] + "§e seconds.");
            timeLeft[0]--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
    }

    private void checkCountdownViability() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers && phase == EventPhase.COUNTDOWN) {
            cancelCountdown();
            phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
            plugin.getChatManager().broadcastToParticipants("§cCountdown cancelled: not enough players.");
        }
    }

    private void startEvent() {
        // FIX: use queuedPlayers (who are present at start) instead of readyPlayers
        // (which may have been cleared already), and verify arena spawns are set.
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers || !plugin.getEventArenaManager().hasArenaReady()) {
            plugin.getChatManager().broadcastToParticipants("§cCannot start event: arena spawns not configured or not enough players.");
            phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
            return;
        }

        hardResetRuntimeState(false);

        VoteManager.RoundModifier modifier = voteManager.getActiveModifier();
        voteManager.reset();

        phase = EventPhase.ACTIVE;
        eventStartMillis = System.currentTimeMillis();

        // Use queuedPlayers snapshot — readyPlayers is unreliable here as it
        // may have been partially cleared during the countdown phase.
        List<UUID> participants = new ArrayList<>(queuedPlayers);
        queuedPlayers.clear();
        readyPlayers.clear();
        Collections.shuffle(participants);

        int vampireCount = Math.max(1, plugin.getConfig().getInt("event.roles.initial-vampires", 1));

        if (modifier == VoteManager.RoundModifier.DOUBLE_VAMPIRES) {
            vampireCount = Math.max(1, vampireCount * 2);
            plugin.getChatManager().broadcastToParticipants("§6[Modifier] §eDouble Vampires is active this round!");
        }

        vampireCount = Math.min(vampireCount, Math.max(1, participants.size() - 1));

        for (int i = 0; i < participants.size(); i++) {
            UUID playerId = participants.get(i);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;

            savePlayerState(player);
            preparePlayerForEvent(player, true);

            activePlayers.add(playerId);
            payoutEligibleActivePlayers.add(playerId);
            roundAliveStart.put(playerId, System.currentTimeMillis());
            plugin.getEventStatsManager().incrementEventsPlayed(playerId);

            if (i < vampireCount) {
                vampires.add(playerId);
                originalVampires.add(playerId);
                defaultClassIfMissing(playerId, true);
                teleportProtected(player, plugin.getEventArenaManager().getVampireSpawn());
                giveRoleKit(player);
                applyStartInvisibility(player);
                applyPermanentVampireVision(player);
                player.sendTitle("§5VAMPIRE", "§7Convert all hunters", 0, 60, 10);
            } else {
                hunters.add(playerId);
                originalHunters.add(playerId);
                defaultClassIfMissing(playerId, false);
                teleportProtected(player, plugin.getEventArenaManager().getHunterSpawn());
                giveRoleKit(player);
                if (modifier != VoteManager.RoundModifier.NO_COMPASS) giveTracker(player);
                player.sendTitle("§bHUNTER", "§7Kill all vampires", 0, 60, 10);
                hunterCampReferenceLocations.put(playerId, player.getLocation().clone());
                hunterCampReferenceMillis.put(playerId, System.currentTimeMillis());
                hunterCampStage.put(playerId, 0);
            }
        }

        // FIX: if after assigning roles one team is empty (e.g. all players offline),
        // abort gracefully instead of ending with a false win condition.
        if (vampires.isEmpty() || hunters.isEmpty()) {
            plugin.getLogger().warning("Event aborted after role assignment: vampires=" + vampires.size() + ", hunters=" + hunters.size() + ". Not enough online players.");
            plugin.getChatManager().broadcastGlobal("§cEvent cancelled: not enough online players to form both teams.");
            endEvent(EventWinner.NONE, true);
            return;
        }

        configuredDurationSeconds = Math.max(30L, plugin.getConfig().getLong("event.duration-seconds", 600L));

        if (modifier == VoteManager.RoundModifier.SUDDEN_DEATH) {
            configuredDurationSeconds = Math.max(30L, configuredDurationSeconds / 2);
            plugin.getChatManager().broadcastToParticipants("§6[Modifier] §eSudden Death — timer halved!");
        }

        eventEndMillis = System.currentTimeMillis() + (configuredDurationSeconds * 1000L);

        createBossBar(configuredDurationSeconds);
        startTimerTask();
        startTrackerTask();
        startAmbianceTask();

        if (modifier != VoteManager.RoundModifier.FOG_OF_WAR) {
            startOpeningReveal();
        } else {
            plugin.getChatManager().broadcastToParticipants("§6[Modifier] §eFog of War — vampires are not revealed at the start!");
        }

        plugin.getChatManager().broadcastGlobal("§cA Vampire Hunt event has started!");
        for (UUID id : activePlayers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline())
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        }

        plugin.getLogger().info("Event started: active=" + activePlayers.size()
                + ", vampires=" + vampires.size()
                + ", hunters=" + hunters.size());

        // FIX: delay win-condition check by 1 second so all role assignments
        // and teleports have fully processed before evaluation.
        Bukkit.getScheduler().runTaskLater(plugin, this::checkWinConditions, 20L);
    }

    private void endEvent(EventWinner winner, boolean forced) {
        if (phase == EventPhase.ENDING || phase == EventPhase.IDLE) return;

        phase = EventPhase.ENDING;

        cancelCountdown();
        stopTrackerTask();
        stopAmbianceTask();
        stopTimerTask();
        cancelOpeningReveal();
        removeBossBar();

        if (!forced) announceRoundMVPs();

        String winnerLine = switch (winner) {
            case HUNTERS -> "§bHunters win! The night is safe.";
            case VAMPIRES -> "§5Vampires win! The hunt ends in darkness.";
            default -> "§7The event ended with no winner.";
        };

        plugin.getChatManager().broadcastGlobal(PREFIX + winnerLine);

        if (!forced) distributePayouts(winner);
        if (!forced) voteManager.openVote();

        Set<UUID> allParticipants = new HashSet<>();
        allParticipants.addAll(activePlayers);
        allParticipants.addAll(spectatorPlayers);
        allParticipants.addAll(disconnectedPlayers);

        for (UUID playerId : allParticipants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                restorePlayer(player);
                teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            } else {
                pendingReturnTeleport.add(playerId);
            }
        }

        hardResetRuntimeState(true);
        phase = EventPhase.IDLE;
    }

    private void announceRoundMVPs() {
        UUID topVampire = null; int topVampireScore = -1;
        for (UUID id : originalVampires) {
            int score = roundKills.getOrDefault(id, 0) + roundInfections.getOrDefault(id, 0);
            if (score > topVampireScore) { topVampireScore = score; topVampire = id; }
        }

        UUID topHunter = null; int topHunterKills = -1;
        for (UUID id : originalHunters) {
            int kills = roundKills.getOrDefault(id, 0);
            if (kills > topHunterKills) { topHunterKills = kills; topHunter = id; }
        }

        final String vampireMvpName = topVampire != null ? resolveName(topVampire) : null;
        final String hunterMvpName = topHunter != null ? resolveName(topHunter) : null;
        final int finalVampireScore = topVampireScore;
        final int finalHunterKills = topHunterKills;

        Set<UUID> audience = new HashSet<>(activePlayers);
        audience.addAll(spectatorPlayers);

        for (UUID id : audience) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;

            String subtitle;
            if (vampireMvpName != null && hunterMvpName != null)
                subtitle = "§5" + vampireMvpName + " §8(" + finalVampireScore + " pts)  §8|  §b" + hunterMvpName + " §8(" + finalHunterKills + " kills)";
            else if (vampireMvpName != null)
                subtitle = "§5Vampire MVP: " + vampireMvpName + " §8(" + finalVampireScore + " pts)";
            else if (hunterMvpName != null)
                subtitle = "§bHunter MVP: " + hunterMvpName + " §8(" + finalHunterKills + " kills)";
            else
                subtitle = "§7No MVP this round.";

            p.sendTitle("§6Round Over", subtitle, 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        }

        if (vampireMvpName != null) plugin.getChatManager().broadcastToParticipants("§5Vampire MVP: §f" + vampireMvpName + " §8(kills+infections: " + finalVampireScore + ")");
        if (hunterMvpName != null) plugin.getChatManager().broadcastToParticipants("§bHunter MVP: §f" + hunterMvpName + " §8(kills: " + finalHunterKills + ")");

        if (topVampire != null && finalVampireScore > 0) checkAndGrantCosmeticTitle(topVampire, "§5Shadow Lord", "top-vampire-mvp");
        if (topHunter != null && finalHunterKills > 0) checkAndGrantCosmeticTitle(topHunter, "§bVampire Slayer", "top-hunter-mvp");
    }

    private void checkAndGrantCosmeticTitle(UUID playerId, String title, String unlockKey) {
        if (plugin.getEventStatsManager().hasCosmeticTitle(playerId, unlockKey)) return;
        plugin.getEventStatsManager().grantCosmeticTitle(playerId, unlockKey, title);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.sendMessage(PREFIX + "§6✦ New title unlocked: " + title + " §6✦");
            player.sendTitle("§6Title Unlocked", title, 10, 60, 15);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
        }
    }

    private void infectHunter(Player victim, Player killer) {
        UUID victimId = victim.getUniqueId();

        hunters.remove(victimId);
        vampires.add(victimId);
        hunterCampReferenceLocations.remove(victimId);
        hunterCampReferenceMillis.remove(victimId);
        hunterCampStage.remove(victimId);
        hunterCampRevealCooldownUntil.remove(victimId);

        roundInfections.merge(killer.getUniqueId(), 1, Integer::sum);
        plugin.getEventStatsManager().addInfection(killer.getUniqueId());
        recordKill(killer, victim);

        selectedClasses.put(victimId, RoleClass.VAMPIRE_STALKER);

        preparePlayerForEvent(victim, true);
        teleportProtected(victim, plugin.getEventArenaManager().getVampireSpawn());
        giveRoleKit(victim);
        applyStartInvisibility(victim);
        applyPermanentVampireVision(victim);

        victim.sendTitle("§5INFECTED", "§7You are now a vampire", 0, 60, 10);
        plugin.getChatManager().sendPrefixed(victim, "§5You were infected and turned into a vampire.");
        playKillEffects(killer, victim, true);
        applyVampireKillInvisibility(killer);
        plugin.getChatManager().broadcastToParticipants("§5" + killer.getName() + " infected §f" + victim.getName() + "§5!");
    }

    private void applyStartInvisibility(Player vampire) {
        if (vampire == null || !vampire.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.vampire.start-invisibility.enabled", true);
        if (!enabled) return;

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.start-invisibility.seconds", 60));
        if (getSelectedClass(vampire.getUniqueId()) == RoleClass.VAMPIRE_STALKER)
            seconds += Math.max(5, plugin.getConfig().getInt("event.classes.vampire-stalker.bonus-invisibility-seconds", 15));

        vampire.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, seconds * 20, 0, false, false, false));
        plugin.getChatManager().sendPrefixed(vampire, "§5You are invisible for the first §f" + seconds + "§5 seconds. Dark armor is recommended because vanilla armor can still be seen.");
    }

    private void applyPermanentVampireVision(Player vampire) {
        if (vampire == null || !vampire.isOnline()) return;
        vampire.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
    }

    private void reapplyCurrentVampireInvisibility(Player vampire) {
        if (vampire == null || !vampire.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.vampire.start-invisibility.enabled", true);
        if (!enabled) return;

        long remainingMillis = Math.max(0L, eventEndMillis - System.currentTimeMillis());
        long elapsedSeconds = Math.max(0L, configuredDurationSeconds - (remainingMillis / 1000L));
        int startSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire.start-invisibility.seconds", 60));
        if (getSelectedClass(vampire.getUniqueId()) == RoleClass.VAMPIRE_STALKER)
            startSeconds += Math.max(5, plugin.getConfig().getInt("event.classes.vampire-stalker.bonus-invisibility-seconds", 15));

        long remainingStartInvisible = Math.max(0L, startSeconds - elapsedSeconds);
        if (remainingStartInvisible > 0L)
            vampire.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) remainingStartInvisible * 20, 0, false, false, false));

        applyPermanentVampireVision(vampire);
    }

    private void applyVampireKillInvisibility(Player killer) {
        if (killer == null || !killer.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.vampire.invisibility-on-kill", true);
        if (!enabled) return;

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.invisibility-seconds", 30));
        if (getSelectedClass(killer.getUniqueId()) == RoleClass.VAMPIRE_STALKER) seconds += 5;

        killer.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, seconds * 20, 0, false, false, false));
        plugin.getChatManager().sendPrefixed(killer, "§5You gained invisibility for §f" + seconds + "§5 seconds.");
    }

    private void recordKill(Player killer, Player victim) {
        if (killer == null || victim == null) return;
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();

        roundKills.merge(killerId, 1, Integer::sum);

        if (isVampire(killerId) && isHunter(victimId)) {
            plugin.getEventStatsManager().addVampireKill(killerId);
            applyVampireKillInvisibility(killer);
        } else if (isHunter(killerId) && isVampire(victimId)) {
            plugin.getEventStatsManager().addHunterKill(killerId);
        }
    }

    private void playKillEffects(Player killer, Player victim, boolean infection) {
        if (victim == null || victim.getWorld() == null) return;
        Location loc = victim.getLocation().clone().add(0, 1.0, 0);
        World world = victim.getWorld();

        boolean particles = plugin.getConfig().getBoolean("event.kill-effects.red-particles", true);
        boolean lightning = plugin.getConfig().getBoolean("event.kill-effects.lightning-visual", true);
        boolean nausea = plugin.getConfig().getBoolean("event.kill-effects.screen-shake", true);
        boolean broadcast = plugin.getConfig().getBoolean("event.kill-effects.special-broadcast", true);

        if (particles) world.spawnParticle(Particle.DUST, loc, 40, 0.35, 0.5, 0.35, 0.0, new Particle.DustOptions(Color.RED, 1.5f));
        if (lightning) world.strikeLightningEffect(loc);
        if (victim.isOnline() && nausea) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 30, 0, false, false, false));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1, false, false, false));
        }
        if (killer != null && killer.isOnline()) {
            killer.playSound(killer.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.4f);
            killer.playSound(killer.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.6f, 0.6f);
        }
        if (broadcast && killer != null) {
            String line = infection
                    ? "§5" + killer.getName() + " infected §f" + victim.getName() + " §8(First blood for the night...)"
                    : "§c" + killer.getName() + " killed §f" + victim.getName() + "§c.";
            plugin.getChatManager().broadcastToParticipants(line);
        }
    }

    private void eliminateDisconnectedPlayerImmediately(Player player) {
        eliminatePlayer(player.getUniqueId(), true, true, "§cYou disconnected during the event and were eliminated.");
        checkWinConditions();
    }

    private void eliminateOfflinePlayer(UUID playerId, boolean restoreState, String message) {
        activePlayers.remove(playerId);
        vampires.remove(playerId);
        hunters.remove(playerId);
        payoutEligibleActivePlayers.remove(playerId);
        hunterCampReferenceLocations.remove(playerId);
        hunterCampReferenceMillis.remove(playerId);
        hunterCampStage.remove(playerId);
        hunterCampRevealCooldownUntil.remove(playerId);

        boolean spectatorEnabled = plugin.getConfig().getBoolean("event.spectator.enabled", true);
        if (spectatorEnabled) { spectatorPlayers.add(playerId); payoutEligibleSpectators.add(playerId); return; }

        pendingReturnTeleport.add(playerId);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            if (message != null && !message.isBlank()) online.sendMessage(PREFIX + message);
            if (restoreState) { restorePlayer(online); teleportProtected(online, plugin.getEventArenaManager().getReturnSpawn()); }
        }
    }

    private void sendToOfflineSpectator(UUID playerId) {
        activePlayers.remove(playerId);
        vampires.remove(playerId);
        hunters.remove(playerId);
        payoutEligibleActivePlayers.remove(playerId);
        spectatorPlayers.add(playerId);
        payoutEligibleSpectators.add(playerId);
        hunterCampReferenceLocations.remove(playerId);
        hunterCampReferenceMillis.remove(playerId);
        hunterCampStage.remove(playerId);
        hunterCampRevealCooldownUntil.remove(playerId);
    }

    private void eliminatePlayer(UUID playerId, boolean restoreState, boolean leftEvent, String message) {
        activePlayers.remove(playerId);
        vampires.remove(playerId);
        hunters.remove(playerId);
        disconnectedPlayers.remove(playerId);
        cancelDisconnectTask(playerId);
        hunterCampReferenceLocations.remove(playerId);
        hunterCampReferenceMillis.remove(playerId);
        hunterCampStage.remove(playerId);
        hunterCampRevealCooldownUntil.remove(playerId);

        boolean spectatorEnabled = plugin.getConfig().getBoolean("event.spectator.enabled", true);
        Player player = Bukkit.getPlayer(playerId);

        if (player != null && player.isOnline()) {
            if (message != null && !message.isBlank()) player.sendMessage(PREFIX + message);
            if (!leftEvent && spectatorEnabled && plugin.getEventArenaManager().getSpectatorSpawn() != null) { sendToSpectatorMode(player, true); return; }
            markSpectatorPayoutIneligible(playerId);
            if (eventBossBar != null) eventBossBar.removePlayer(player);
            if (restoreState) { restorePlayer(player); teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn()); }
        } else {
            if (!leftEvent && spectatorEnabled) { spectatorPlayers.add(playerId); payoutEligibleSpectators.add(playerId); }
            else if (restoreState || leftEvent) { pendingReturnTeleport.add(playerId); markSpectatorPayoutIneligible(playerId); }
        }
    }

    private void sendToSpectatorMode(Player player, boolean payoutEligible) {
        if (player == null || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        spectatorPlayers.add(playerId);
        disconnectedPlayers.remove(playerId);
        if (payoutEligible) payoutEligibleSpectators.add(playerId);
        else payoutEligibleSpectators.remove(playerId);
        payoutEligibleActivePlayers.remove(playerId);

        clearEventState(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFireTicks(0);
        teleportProtected(player, plugin.getEventArenaManager().getSpectatorSpawn());
        if (eventBossBar != null) eventBossBar.addPlayer(player);
        player.sendTitle("§7ELIMINATED", "§fYou are now spectating", 0, 50, 10);
        player.sendMessage(PREFIX + "§7You are now spectating above the arena.");
        player.sendMessage(PREFIX + "§eUse /vhunt specteleport next, /vhunt specteleport hunters, or /vhunt specteleport vampires.");
        player.sendMessage(PREFIX + "§eUse /vhunt teams to see live counts.");
        player.sendMessage(PREFIX + "§eUse /vhunt leave to leave spectator mode, but you will lose payout eligibility.");
    }

    private void checkWinConditions() {
        if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;

        if (vampires.isEmpty() && !hunters.isEmpty()) { endEvent(EventWinner.HUNTERS, false); return; }
        if (hunters.isEmpty() && !vampires.isEmpty()) { endEvent(EventWinner.VAMPIRES, false); return; }
        if (activePlayers.isEmpty()) endEvent(EventWinner.NONE, false);
    }

    private void startTimerTask() {
        stopTimerTask();
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) { stopTimerTask(); return; }

            long now = System.currentTimeMillis();
            long remaining = Math.max(0L, eventEndMillis - now);

            updateBossBarProgress(remaining);

            if (remaining <= 0L) {
                stopTimerTask();
                boolean suddenDeathEnabled = plugin.getConfig().getBoolean("event.sudden-death.enabled", true);
                if (suddenDeathEnabled && phase == EventPhase.ACTIVE) {
                    triggerSuddenDeath();
                } else {
                    endEvent(EventWinner.VAMPIRES, false);
                }
                return;
            }

            long remainingSeconds = remaining / 1000L;

            if (phase == EventPhase.ACTIVE) {
                int sdWarning = plugin.getConfig().getInt("event.sudden-death.warning-seconds", 30);
                if (remainingSeconds == sdWarning) {
                    plugin.getChatManager().broadcastToParticipants("§c§lSudden Death begins in §f" + sdWarning + "§c§l seconds!");
                    for (UUID id : activePlayers) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f);
                    }
                }
            }

            checkHunterCampReveals();

        }, 0L, 20L);
    }

    private void stopTimerTask() {
        if (timerTask != null) { timerTask.cancel(); timerTask = null; }
    }

    private void triggerSuddenDeath() {
        phase = EventPhase.SUDDEN_DEATH;
        suddenDeathStartMillis = System.currentTimeMillis();
        int extraSeconds = Math.max(30, plugin.getConfig().getInt("event.sudden-death.extra-seconds", 120));
        eventEndMillis = System.currentTimeMillis() + (extraSeconds * 1000L);

        plugin.getChatManager().broadcastToParticipants("§c§lSUDDEN DEATH! §eTimer extended by §f" + extraSeconds + "§e seconds. All vampires revealed!");

        for (UUID id : vampires) {
            Player v = Bukkit.getPlayer(id);
            if (v != null && v.isOnline()) {
                v.removePotionEffect(PotionEffectType.INVISIBILITY);
                v.sendTitle("§4SUDDEN DEATH", "§cYour location is revealed", 0, 60, 10);
            }
        }

        for (UUID id : hunters) {
            Player h = Bukkit.getPlayer(id);
            if (h != null && h.isOnline()) h.sendTitle("§4SUDDEN DEATH", "§eVampires are revealed!", 0, 60, 10);
        }

        startTimerTask();
        checkWinConditions();
    }

    private void createBossBar(long durationSeconds) {
        removeBossBar();
        boolean enabled = plugin.getConfig().getBoolean("event.bossbar.enabled", true);
        if (!enabled) return;

        String title = plugin.getConfig().getString("event.bossbar.title", "§4Vampire Hunt §8| §7Time Remaining");
        eventBossBar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
        eventBossBar.setProgress(1.0);

        for (UUID id : activePlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) eventBossBar.addPlayer(p);
        }
        for (UUID id : spectatorPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) eventBossBar.addPlayer(p);
        }
    }

    private void updateBossBarProgress(long remainingMillis) {
        if (eventBossBar == null) return;
        long totalMillis = configuredDurationSeconds * 1000L;
        if (totalMillis <= 0) return;
        double progress = Math.max(0.0, Math.min(1.0, (double) remainingMillis / totalMillis));
        eventBossBar.setProgress(progress);

        long secs = remainingMillis / 1000L;
        long mins = secs / 60;
        long sec = secs % 60;
        String timeStr = String.format("%d:%02d", mins, sec);
        String title = plugin.getConfig().getString("event.bossbar.title", "§4Vampire Hunt §8| §7Time Remaining");
        eventBossBar.setTitle(title + " §8[§f" + timeStr + "§8]");

        if (phase == EventPhase.SUDDEN_DEATH) {
            eventBossBar.setColor(BarColor.RED);
        } else if (progress < 0.25) {
            eventBossBar.setColor(BarColor.RED);
        } else if (progress < 0.5) {
            eventBossBar.setColor(BarColor.YELLOW);
        } else {
            eventBossBar.setColor(BarColor.GREEN);
        }
    }

    private void removeBossBar() {
        if (eventBossBar != null) { eventBossBar.removeAll(); eventBossBar = null; }
    }

    private void startTrackerTask() {
        stopTrackerTask();
        boolean enabled = plugin.getConfig().getBoolean("event.hunter-tracker.enabled", true);
        if (!enabled) return;

        int intervalTicks = Math.max(5, plugin.getConfig().getInt("event.hunter-tracker.update-ticks", 20));

        trackerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;

            for (UUID hunterId : hunters) {
                Player hunter = Bukkit.getPlayer(hunterId);
                if (hunter == null || !hunter.isOnline()) continue;

                Player nearest = findNearestVisibleVampire(hunter, Double.MAX_VALUE);
                if (nearest != null) hunter.setCompassTarget(nearest.getLocation());
            }
        }, 0L, intervalTicks);
    }

    private void stopTrackerTask() {
        if (trackerTask != null) { trackerTask.cancel(); trackerTask = null; }
    }

    private Player findNearestVisibleVampire(Player hunter, double maxDistance) {
        Player nearest = null;
        double nearestDist = maxDistance * maxDistance;
        for (UUID vid : vampires) {
            Player v = Bukkit.getPlayer(vid);
            if (v == null || !v.isOnline()) continue;
            if (!v.getWorld().equals(hunter.getWorld())) continue;
            double dist = hunter.getLocation().distanceSquared(v.getLocation());
            if (dist < nearestDist) { nearestDist = dist; nearest = v; }
        }
        return nearest;
    }

    private void startAmbianceTask() {
        stopAmbianceTask();
        boolean enabled = plugin.getConfig().getBoolean("event.horror.ambiance.enabled", true);
        if (!enabled) return;

        int intervalSeconds = Math.max(10, plugin.getConfig().getInt("event.horror.ambiance.interval-seconds", 45));

        ambianceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;

            Sound[] ambientSounds = {
                    Sound.AMBIENT_CAVE,
                    Sound.ENTITY_BAT_AMBIENT,
                    Sound.ENTITY_ENDERMAN_AMBIENT,
            };

            for (UUID hunterId : hunters) {
                Player hunter = Bukkit.getPlayer(hunterId);
                if (hunter == null || !hunter.isOnline()) continue;
                Sound sound = ambientSounds[(int) (Math.random() * ambientSounds.length)];
                hunter.playSound(hunter.getLocation(), sound, 0.4f, (float) (0.8 + Math.random() * 0.4));
            }
        }, 0L, intervalSeconds * 20L);
    }

    private void stopAmbianceTask() {
        if (ambianceTask != null) { ambianceTask.cancel(); ambianceTask = null; }
    }

    private void startOpeningReveal() {
        cancelOpeningReveal();
        int revealSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire.opening-reveal-seconds", 10));
        openingRevealEndsAt = System.currentTimeMillis() + (revealSeconds * 1000L);

        plugin.getChatManager().broadcastToParticipants("§5Vampires are revealed for the first §f" + revealSeconds + "§5 seconds!");
        revealNearbyVampires(null, Double.MAX_VALUE, revealSeconds * 20);

        openingRevealEndTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            openingRevealEndTask = null;
            openingRevealEndsAt = 0L;
        }, revealSeconds * 20L);
    }

    private void cancelOpeningReveal() {
        if (openingRevealEndTask != null) { openingRevealEndTask.cancel(); openingRevealEndTask = null; }
        openingRevealEndsAt = 0L;
    }

    private void revealNearbyVampires(Player revealer, double radius, int durationTicks) 