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

    private static final String PREFIX = "§8[§4Vampire Hunt§8] §7";

    private final DraculaVampireHunt plugin;

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
    }

    public boolean joinEvent(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

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

        if (phase == EventPhase.IDLE) {
            phase = EventPhase.QUEUEING;
        }

        if (queuedPlayers.size() >= minPlayers) {
            beginReadyCheckIfPossible();
        }

        return true;
    }

    public boolean leaveEvent(Player player, boolean restoreState) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        if (queuedPlayers.remove(playerId)) {
            readyPlayers.remove(playerId);
            selectedClasses.remove(playerId);
            plugin.getChatManager().sendPrefixed(player, "§eYou left the event queue.");

            if (restoreState) {
                teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            }

            checkCountdownViability();
            if (queuedPlayers.isEmpty() && (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK || phase == EventPhase.COUNTDOWN)) {
                phase = EventPhase.IDLE;
            }
            return true;
        }

        if (spectatorPlayers.contains(playerId)) {
            markSpectatorPayoutIneligible(playerId);
            spectatorPlayers.remove(playerId);
            disconnectedPlayers.remove(playerId);
            cancelDisconnectTask(playerId);

            if (eventBossBar != null) {
                eventBossBar.removePlayer(player);
            }

            if (restoreState) {
                restorePlayer(player);
                teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            }

            plugin.getChatManager().sendPrefixed(player, "§eYou left spectator mode and gave up payout eligibility.");
            return true;
        }

        if (!activePlayers.contains(playerId) && !disconnectedPlayers.contains(playerId)) {
            return false;
        }

        eliminatePlayer(playerId, true, true, "§cYou left the event.");
        checkWinConditions();
        return true;
    }

    public boolean setPlayerReady(Player player) {
        if (player == null || !queuedPlayers.contains(player.getUniqueId())) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        if (readyPlayers.contains(playerId)) {
            plugin.getChatManager().sendPrefixed(player, "§eYou are already marked ready.");
            return false;
        }

        readyPlayers.add(playerId);
        plugin.getChatManager().sendPrefixed(player, "§aYou are now ready.");

        if (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK) {
            beginReadyCheckIfPossible();
        }

        if (allQueuedPlayersReady() && phase == EventPhase.READY_CHECK) {
            plugin.getChatManager().broadcastToParticipants("§aAll players are ready.");
            startCountdown();
        }

        return true;
    }

    public boolean unreadyPlayer(Player player) {
        if (player == null || !queuedPlayers.contains(player.getUniqueId())) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        if (!readyPlayers.remove(playerId)) {
            plugin.getChatManager().sendPrefixed(player, "§eYou were not marked ready.");
            return false;
        }

        plugin.getChatManager().sendPrefixed(player, "§eYou are no longer ready.");
        if (phase == EventPhase.COUNTDOWN) {
            cancelCountdown();
            phase = EventPhase.READY_CHECK;
            plugin.getChatManager().broadcastToParticipants("§cCountdown paused because someone unreadied.");
        }
        return true;
    }

    public boolean startEventByAdmin(CommandSource source) {
        if (phase == EventPhase.ACTIVE || phase == EventPhase.COUNTDOWN || phase == EventPhase.SUDDEN_DEATH) {
            return false;
        }

        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) {
            return false;
        }

        if (plugin.getConfig().getBoolean("event.ready-check.enabled", true)) {
            readyPlayers.addAll(queuedPlayers);
        }

        startCountdown(3);
        return true;
    }

    public boolean stopEventGracefully() {
        if (!isEventOngoing() && queuedPlayers.isEmpty()) {
            return false;
        }

        endEvent(EventWinner.NONE, true);
        return true;
    }

    public boolean forceStopEvent() {
        if (!isEventOngoing() && queuedPlayers.isEmpty()) {
            return false;
        }

        endEvent(EventWinner.NONE, true);
        return true;
    }

    public void shutdown() {
        endEvent(EventWinner.NONE, true);
    }

    public void handlePlayerJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

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

            if (eventBossBar != null) {
                eventBossBar.addPlayer(player);
            }

            plugin.getChatManager().sendPrefixed(player, "§7You rejoined as an event spectator.");
            return;
        }

        if (!disconnectedPlayers.contains(playerId)) {
            return;
        }

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

        if (eventBossBar != null) {
            eventBossBar.addPlayer(player);
        }
    }

    public void handlePlayerQuit(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (queuedPlayers.remove(playerId)) {
            readyPlayers.remove(playerId);
            selectedClasses.remove(playerId);
            checkCountdownViability();

            if (queuedPlayers.isEmpty() && (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK)) {
                phase = EventPhase.IDLE;
            }
            return;
        }

        if (spectatorPlayers.contains(playerId)) {
            disconnectedPlayers.add(playerId);
            return;
        }

        if (!activePlayers.contains(playerId)) {
            return;
        }

        boolean graceEnabled = plugin.getConfig().getBoolean("event.disconnect.grace-enabled", true);
        int graceSeconds = Math.max(1, plugin.getConfig().getInt("event.disconnect.grace-seconds", 20));
        boolean eliminateIfNotBack = plugin.getConfig().getBoolean("event.disconnect.eliminate-if-not-back", true);

        if (!graceEnabled) {
            eliminateDisconnectedPlayerImmediately(player);
            return;
        }

        disconnectedPlayers.add(playerId);

        if (eventBossBar != null) {
            eventBossBar.removePlayer(player);
        }

        cancelDisconnectTask(playerId);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            disconnectTimeoutTasks.remove(playerId);

            if (!disconnectedPlayers.contains(playerId)) {
                return;
            }

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
        if (victim == null) {
            return;
        }

        UUID victimId = victim.getUniqueId();
        if (!activePlayers.contains(victimId)) {
            return;
        }

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
        if (damagerEntity == null || victim == null) {
            return;
        }

        if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) {
            return;
        }

        Player attacker = resolveAttackingPlayer(damagerEntity);
        if (attacker == null) {
            return;
        }

        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();

        if (!activePlayers.contains(attackerId) || !activePlayers.contains(victimId)) {
            return;
        }

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

    private Player resolveAttackingPlayer(Entity damagerEntity) {
        if (damagerEntity instanceof Player player) {
            return player;
        }

        if (damagerEntity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    private void applyVampireAttackEffects(Player attacker, Player victim) {
        if (attacker == null || victim == null || !attacker.isOnline() || !victim.isOnline()) {
            return;
        }

        applyVampireDizziness(attacker, victim);
        applyHunterDarknessPulse(victim);
    }

    private void applyVampireDizziness(Player attacker, Player victim) {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.enabled", true);
        if (!enabled) {
            return;
        }

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.attack-effects.dizziness.seconds", 2));
        int amplifier = Math.max(0, plugin.getConfig().getInt("event.vampire.attack-effects.dizziness.amplifier", 0));
        boolean ambient = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.ambient", false);
        boolean particles = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.particles", false);
        boolean icon = plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.icon", true);

        double chancePercent = Math.max(0.0D, Math.min(100.0D,
                plugin.getConfig().getDouble("event.vampire.attack-effects.dizziness.chance-percent", 100.0D)));

        if (chancePercent < 100.0D && Math.random() * 100.0D > chancePercent) {
            return;
        }

        victim.addPotionEffect(new PotionEffect(
                PotionEffectType.NAUSEA,
                seconds * 20,
                amplifier,
                ambient,
                particles,
                icon
        ));

        if (plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.notify-hunter", true)) {
            victim.sendMessage(PREFIX + "§5A vampire attack made you dizzy for §f" + seconds + "§5 seconds.");
        }

        if (plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.notify-vampire", false)) {
            attacker.sendMessage(PREFIX + "§dYou inflicted dizziness on §f" + victim.getName() + "§d.");
        }
    }

    private void applyHunterDarknessPulse(Player hunter) {
        boolean enabled = plugin.getConfig().getBoolean("event.horror.darkness-pulses.enabled", true);
        if (!enabled || hunter == null || !hunter.isOnline() || !isHunter(hunter.getUniqueId())) {
            return;
        }

        int darknessSeconds = Math.max(1, plugin.getConfig().getInt("event.horror.darkness-pulses.seconds", 2));
        int darknessAmplifier = Math.max(0, plugin.getConfig().getInt("event.horror.darkness-pulses.amplifier", 0));
        int slownessSeconds = Math.max(0, plugin.getConfig().getInt("event.horror.darkness-pulses.slowness-seconds", 1));

        hunter.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darknessSeconds * 20, darknessAmplifier, false, false, false));

        if (slownessSeconds > 0) {
            hunter.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slownessSeconds * 20, 0, false, false, false));
        }

        hunter.playSound(hunter.getLocation(), Sound.AMBIENT_CAVE.value(), 0.6f, 0.8f);
        hunter.sendTitle("", "§8The darkness closes in...", 0, 20, 5);
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

    public boolean isActiveParticipant(UUID playerId) {
        return activePlayers.contains(playerId);
    }

    public boolean isVampire(UUID playerId) {
        return vampires.contains(playerId);
    }

    public boolean isHunter(UUID playerId) {
        return hunters.contains(playerId);
    }

    public boolean isSpectatorParticipant(UUID playerId) {
        return spectatorPlayers.contains(playerId);
    }

    public boolean isSpectatorPayoutEligible(UUID playerId) {
        return payoutEligibleSpectators.contains(playerId) || payoutEligibleActivePlayers.contains(playerId);
    }

    public void markSpectatorPayoutIneligible(UUID playerId) {
        payoutEligibleSpectators.remove(playerId);
        payoutEligibleActivePlayers.remove(playerId);
    }

    public boolean isProtectedSpectatorTeleport(PlayerTeleportEvent event) {
        if (event == null || event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return false;
        }

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

    public Set<UUID> getSpectatorIds() {
        return Collections.unmodifiableSet(spectatorPlayers);
    }

    public String getPhaseName() {
        return phase.name().toLowerCase(Locale.ROOT);
    }

    public int getQueuedCount() {
        return queuedPlayers.size();
    }

    public int getReadyCount() {
        return readyPlayers.size();
    }

    public int getActiveCount() {
        return activePlayers.size();
    }

    public int getVampireCount() {
        return vampires.size();
    }

    public int getHunterCount() {
        return hunters.size();
    }

    public RoleClass getSelectedClass(UUID playerId) {
        return selectedClasses.getOrDefault(playerId, RoleClass.NONE);
    }

    public boolean selectClass(Player player, String input) {
        if (player == null || input == null || input.isBlank()) {
            return false;
        }

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
        if (player == null || !activePlayers.contains(player.getUniqueId())) {
            return false;
        }

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
                if (!isHunter(playerId)) {
                    plugin.getChatManager().sendPrefixed(player, "§cOnly hunters can use this class ability.");
                    return false;
                }
                revealNearbyVampires(player, 28.0D, 4);
                player.sendTitle("§bHoly Reveal", "§7Nearby vampires exposed", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 35000L);
                return true;
            }
            case HUNTER_TRACKER -> {
                if (!isHunter(playerId)) {
                    plugin.getChatManager().sendPrefixed(player, "§cOnly hunters can use this class ability.");
                    return false;
                }
                Player nearest = findNearestVisibleVampire(player, 80.0D);
                if (nearest == null) {
                    plugin.getChatManager().sendPrefixed(player, "§eNo vampire detected.");
                    classAbilityCooldowns.put(playerId, now + 10000L);
                    return true;
                }
                player.setCompassTarget(nearest.getLocation());
                player.sendTitle("§bTracker Pulse", "§f" + nearest.getName() + " located", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 15000L);
                return true;
            }
            case VAMPIRE_STALKER -> {
                if (!isVampire(playerId)) {
                    plugin.getChatManager().sendPrefixed(player, "§cOnly vampires can use this class ability.");
                    return false;
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 10 * 20, 0, false, false, false));
                player.sendTitle("§5Stalker Veil", "§7You vanish into the dark", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 30000L);
                return true;
            }
            case VAMPIRE_BRUTE -> {
                if (!isVampire(playerId)) {
                    plugin.getChatManager().sendPrefixed(player, "§cOnly vampires can use this class ability.");
                    return false;
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 8 * 20, 0, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 8 * 20, 0, false, false, true));
                player.sendTitle("§4Brute Rage", "§7Power over speed", 0, 30, 10);
                classAbilityCooldowns.put(playerId, now + 40000L);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public Player getNextSpectatorTarget(Player spectator, boolean vampiresOnly, boolean huntersOnly) {
        if (spectator == null || !spectatorPlayers.contains(spectator.getUniqueId())) {
            return null;
        }

        List<Player> candidates = new ArrayList<>();
        Set<UUID> source = activePlayers;

        for (UUID uuid : source) {
            if (vampiresOnly && !vampires.contains(uuid)) {
                continue;
            }
            if (huntersOnly && !hunters.contains(uuid)) {
                continue;
            }

            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                candidates.add(player);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        Entity current = spectator.getSpectatorTarget();
        if (current instanceof Player currentPlayer) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getUniqueId().equals(currentPlayer.getUniqueId())) {
                    return candidates.get((i + 1) % candidates.size());
                }
            }
        }

        return candidates.get(0);
    }

    public String getTeamCountLine() {
        return "§7Hunters: §b" + getHunterCount() + " §8| §7Vampires: §5" + getVampireCount() + " §8| §7Spectators: §f" + spectatorPlayers.size();
    }

    public boolean shouldBlockCommand(UUID playerId, String rawCommand) {
        if (!activePlayers.contains(playerId) && !spectatorPlayers.contains(playerId) && !queuedPlayers.contains(playerId)) {
            return false;
        }

        List<String> blocked = plugin.getConfig().getStringList("event.blocked-commands");
        String normalized = rawCommand == null ? "" : rawCommand.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        String base = normalized.split(" ")[0];
        for (String cmd : blocked) {
            if (base.equalsIgnoreCase(cmd)) {
                return true;
            }
        }

        return false;
    }

    public boolean areSameRole(UUID first, UUID second) {
        return (vampires.contains(first) && vampires.contains(second))
                || (hunters.contains(first) && hunters.contains(second));
    }

    public void giveTracker(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("event.hunter-tracker.enabled", true);
        if (!enabled || !isHunter(player.getUniqueId())) {
            return;
        }

        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bVampire Tracker");
            List<String> lore = new ArrayList<>();
            lore.add("§7Tracks the nearest vampire.");
            if (getSelectedClass(player.getUniqueId()) == RoleClass.HUNTER_TRACKER) {
                lore.add("§bTracker Class: enhanced tracking.");
            }
            compass.setItemMeta(meta);
        }

        PlayerInventory inv = player.getInventory();
        if (!inv.contains(Material.COMPASS)) {
            inv.addItem(compass);
        }
    }

    private void beginReadyCheckIfPossible() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("event.ready-check.enabled", true);
        if (!enabled) {
            startCountdown();
            return;
        }

        if (phase != EventPhase.READY_CHECK && phase != EventPhase.COUNTDOWN) {
            phase = EventPhase.READY_CHECK;
            readyCountdownSecondsLeft = Math.max(10, plugin.getConfig().getInt("event.ready-check.timeout-seconds", 25));
        }

        if (countdownTask != null && phase == EventPhase.COUNTDOWN) {
            return;
        }

        if (phase == EventPhase.READY_CHECK && countdownTask == null) {
            countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                int min = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
                if (queuedPlayers.size() < min) {
                    cancelCountdown();
                    phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
                    return;
                }

                if (allQueuedPlayersReady()) {
                    cancelCountdown();
                    startCountdown();
                    return;
                }

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

    private void startCountdown() {
        int seconds = Math.max(5, plugin.getConfig().getInt("event.countdown-seconds", 15));
        startCountdown(seconds);
    }

    private void startCountdown(int seconds) {
        cancelCountdown();

        if (!plugin.getEventArenaManager().hasArenaReady()) {
            phase = EventPhase.QUEUEING;
            return;
        }

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

            if (plugin.getConfig().getBoolean("event.ready-check.enabled", true) && !allQueuedPlayersReady()) {
                plugin.getChatManager().broadcastToParticipants("§cCountdown paused: not all queued players are ready.");
                cancelCountdown();
                phase = EventPhase.READY_CHECK;
                readyCountdownSecondsLeft = Math.max(10, plugin.getConfig().getInt("event.ready-check.timeout-seconds", 25));
                beginReadyCheckIfPossible();
                return;
            }

            if (timeLeft[0] <= 0) {
                cancelCountdown();
                startEvent();
                return;
            }

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

    private void startEvent() {
        if (queuedPlayers.size() < 2 || !plugin.getEventArenaManager().hasArenaReady()) {
            phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
            return;
        }

        hardResetRuntimeState(false);

        phase = EventPhase.ACTIVE;
        eventStartMillis = System.currentTimeMillis();

        List<UUID> participants = new ArrayList<>(queuedPlayers);
        queuedPlayers.clear();
        readyPlayers.clear();
        Collections.shuffle(participants);

        int vampireCount = Math.max(1, plugin.getConfig().getInt("event.roles.initial-vampires", 1));
        vampireCount = Math.min(vampireCount, Math.max(1, participants.size() - 1));

        for (int i = 0; i < participants.size(); i++) {
            UUID playerId = participants.get(i);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }

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
                giveTracker(player);
                player.sendTitle("§bHUNTER", "§7Kill all vampires", 0, 60, 10);
                hunterCampReferenceLocations.put(playerId, player.getLocation().clone());
                hunterCampReferenceMillis.put(playerId, System.currentTimeMillis());
                hunterCampStage.put(playerId, 0);
            }
        }

        configuredDurationSeconds = Math.max(30L, plugin.getConfig().getLong("event.duration-seconds", 600L));
        eventEndMillis = System.currentTimeMillis() + (configuredDurationSeconds * 1000L);

        createBossBar(configuredDurationSeconds);
        startTrackerTask();
        startAmbianceTask();
        startOpeningReveal();

        plugin.getChatManager().broadcastGlobal("§cA Vampire Hunt event has started!");
        for (UUID id : activePlayers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
            }
        }

        checkWinConditions();
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
        if (vampire == null || !vampire.isOnline()) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("event.vampire.start-invisibility.enabled", true);
        if (!enabled) {
            return;
        }

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.start-invisibility.seconds", 60));
        if (getSelectedClass(vampire.getUniqueId()) == RoleClass.VAMPIRE_STALKER) {
            seconds += Math.max(5, plugin.getConfig().getInt("event.classes.vampire-stalker.bonus-invisibility-seconds", 15));
        }

        vampire.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                seconds * 20,
                0,
                false,
                false,
                false
        ));

        plugin.getChatManager().sendPrefixed(vampire,
                "§5You are invisible for the first §f" + seconds + "§5 seconds. Dark armor is recommended because vanilla armor can still be seen.");
    }

    private void applyPermanentVampireVision(Player vampire) {
        if (vampire == null || !vampire.isOnline()) {
            return;
        }

        vampire.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION,
                Integer.MAX_VALUE,
                0,
                false,
                false,
                false
        ));
    }

    private void reapplyCurrentVampireInvisibility(Player vampire) {
        if (vampire == null || !vampire.isOnline()) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("event.vampire.start-invisibility.enabled", true);
        if (!enabled) {
            return;
        }

        long remainingMillis = Math.max(0L, eventEndMillis - System.currentTimeMillis());
        long elapsedSeconds = Math.max(0L, configuredDurationSeconds - (remainingMillis / 1000L));
        int startSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire.start-invisibility.seconds", 60));
        if (getSelectedClass(vampire.getUniqueId()) == RoleClass.VAMPIRE_STALKER) {
            startSeconds += Math.max(5, plugin.getConfig().getInt("event.classes.vampire-stalker.bonus-invisibility-seconds", 15));
        }

        long remainingStartInvisible = Math.max(0L, startSeconds - elapsedSeconds);

        if (remainingStartInvisible > 0L) {
            vampire.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY,
                    (int) remainingStartInvisible * 20,
                    0,
                    false,
                    false,
                    false
            ));
        }

        applyPermanentVampireVision(vampire);
    }

    private void applyVampireKillInvisibility(Player killer) {
        if (killer == null || !killer.isOnline()) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("event.vampire.invisibility-on-kill", true);
        if (!enabled) {
            return;
        }

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.invisibility-seconds", 30));
        if (getSelectedClass(killer.getUniqueId()) == RoleClass.VAMPIRE_STALKER) {
            seconds += 5;
        }

        killer.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                seconds * 20,
                0,
                false,
                false,
                false
        ));
        plugin.getChatManager().sendPrefixed(killer, "§5You gained invisibility for §f" + seconds + "§5 seconds.");
    }

    private void recordKill(Player killer, Player victim) {
        if (killer == null || victim == null) {
            return;
        }

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
        if (victim == null || victim.getWorld() == null) {
            return;
        }

        Location loc = victim.getLocation().clone().add(0, 1.0, 0);
        World world = victim.getWorld();

        boolean particles = plugin.getConfig().getBoolean("event.kill-effects.red-particles", true);
        boolean lightning = plugin.getConfig().getBoolean("event.kill-effects.lightning-visual", true);
        boolean nausea = plugin.getConfig().getBoolean("event.kill-effects.screen-shake", true);
        boolean broadcast = plugin.getConfig().getBoolean("event.kill-effects.special-broadcast", true);

        if (particles) {
            world.spawnParticle(Particle.DUST, loc, 40, 0.35, 0.5, 0.35, 0.0,
                    new Particle.DustOptions(Color.RED, 1.5f));
        }

        if (lightning) {
            world.strikeLightningEffect(loc);
        }

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
        UUID playerId = player.getUniqueId();
        eliminatePlayer(playerId, true, true, "§cYou disconnected during the event and were eliminated.");
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
        if (spectatorEnabled) {
            spectatorPlayers.add(playerId);
            payoutEligibleSpectators.add(playerId);
            return;
        }

        pendingReturnTeleport.add(playerId);

        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            if (message != null && !message.isBlank()) {
                online.sendMessage(PREFIX + message);
            }
            if (restoreState) {
                restorePlayer(online);
                teleportProtected(online, plugin.getEventArenaManager().getReturnSpawn());
            }
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
            if (message != null && !message.isBlank()) {
                player.sendMessage(PREFIX + message);
            }

            if (!leftEvent && spectatorEnabled && plugin.getEventArenaManager().hasSpectatorSpawn()) {
                sendToSpectatorMode(player, true);
                return;
            }

            markSpectatorPayoutIneligible(playerId);

            if (eventBossBar != null) {
                eventBossBar.removePlayer(player);
            }

            if (restoreState) {
                restorePlayer(player);
                teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());
            }
        } else {
            if (!leftEvent && spectatorEnabled) {
                spectatorPlayers.add(playerId);
                payoutEligibleSpectators.add(playerId);
            } else if (restoreState || leftEvent) {
                pendingReturnTeleport.add(playerId);
                markSpectatorPayoutIneligible(playerId);
            }
        }
    }

    private void sendToSpectatorMode(Player player, boolean payoutEligible) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        spectatorPlayers.add(playerId);
        disconnectedPlayers.remove(playerId);

        if (payoutEligible) {
            payoutEligibleSpectators.add(playerId);
        } else {
            payoutEligibleSpectators.remove(playerId);
        }

        payoutEligibleActivePlayers.remove(playerId);

        clearEventState(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFireTicks(0);

        teleportProtected(player, plugin.getEventArenaManager().getSpectatorSpawn());

        if (eventBossBar != null) {
            eventBossBar.addPlayer(player);
        }

        player.sendTitle("§7ELIMINATED", "§fYou are now spectating", 0, 50, 10);
        player.sendMessage(PREFIX + "§7You are now spectating above the arena.");
        player.sendMessage(PREFIX + "§eUse /vhunt specteleport next, /vhunt specteleport hunters, or /vhunt specteleport vampires.");
        player.sendMessage(PREFIX + "§eUse /vhunt teams to see live counts.");
        player.sendMessage(PREFIX + "§eUse /vhunt leave to leave spectator mode, but you will lose payout eligibility.");
    }

    private void checkWinConditions() {
        if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) {
            return;
        }

        if (vampires.isEmpty() && !hunters.isEmpty()) {
            endEvent(EventWinner.HUNTERS, false);
            return;
        }

        if (hunters.isEmpty() && !vampires.isEmpty()) {
            endEvent(EventWinner.VAMPIRES, false);
            return;
        }

        if (activePlayers.isEmpty() && vampires.isEmpty() && hunters.isEmpty()) {
            endEvent(EventWinner.NONE, true);
        }
    }

    private void createBossBar(long totalSeconds) {
        removeBossBar();

        eventBossBar = Bukkit.createBossBar(
                "§cTime Remaining: §f" + formatTime(totalSeconds),
                BarColor.RED,
                BarStyle.SOLID
        );
        eventBossBar.setVisible(true);

        addAllEligibleViewersToBossBar();

        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) {
                return;
            }

            long remainingMillis = Math.max(0L, eventEndMillis - System.currentTimeMillis());
            long remainingSeconds = remainingMillis / 1000L;
            double progress = configuredDurationSeconds <= 0L
                    ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, (double) remainingSeconds / configuredDurationSeconds));

            if (eventBossBar != null) {
                if (phase == EventPhase.SUDDEN_DEATH) {
                    eventBossBar.setTitle("§4Blood Moon Sudden Death §8- §fFight until one side falls");
                    eventBossBar.setColor(BarColor.PURPLE);
                    eventBossBar.setProgress(1.0D);
                } else {
                    eventBossBar.setProgress(progress);
                    eventBossBar.setTitle("§cTime Remaining: §f" + formatTime(remainingSeconds));
                    eventBossBar.setColor(BarColor.RED);
                }
            }

            refreshBossBarPlayers();

            if (phase == EventPhase.ACTIVE && remainingSeconds <= 0L) {
                if (plugin.getConfig().getBoolean("event.win-conditions.vampires-win-on-timeout", false)) {
                    endEvent(vampires.isEmpty() ? EventWinner.HUNTERS : EventWinner.VAMPIRES, false);
                } else {
                    startSuddenDeath();
                }
            }
        }, 0L, 20L);
    }

    private void startSuddenDeath() {
        if (phase == EventPhase.SUDDEN_DEATH) {
            return;
        }

        phase = EventPhase.SUDDEN_DEATH;
        suddenDeathStartMillis = System.currentTimeMillis();

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter != null && hunter.isOnline()) {
                hunter.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 10 * 20, 0, false, false, false));
                hunter.sendTitle("§4Blood Moon", "§cYou are revealed!", 0, 40, 10);
                hunter.playSound(hunter.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.8f);
            }
        }

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire != null && vampire.isOnline()) {
                vampire.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 12 * 20, 0, false, false, true));
                vampire.sendTitle("§4Blood Moon", "§5The hunt enters sudden death", 0, 40, 10);
                vampire.playSound(vampire.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.7f);
            }
        }

        plugin.getChatManager().broadcastGlobal("§4Blood Moon Sudden Death has begun!");
    }

    private void refreshBossBarPlayers() {
        if (eventBossBar == null) {
            return;
        }

        addAllEligibleViewersToBossBar();
        List<Player> viewers = new ArrayList<>(eventBossBar.getPlayers());

        for (Player viewer : viewers) {
            UUID id = viewer.getUniqueId();
            if (!activePlayers.contains(id) && !spectatorPlayers.contains(id)) {
                eventBossBar.removePlayer(viewer);
            }
        }
    }

    private void addAllEligibleViewersToBossBar() {
        if (eventBossBar == null) {
            return;
        }

        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                eventBossBar.addPlayer(player);
            }
        }

        for (UUID uuid : spectatorPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                eventBossBar.addPlayer(player);
            }
        }
    }

    private void startTrackerTask() {
        stopTrackerTask();

        int intervalTicks = Math.max(10, plugin.getConfig().getInt("event.tracking.tick-interval", 20));

        trackerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) {
                return;
            }

            runHunterTracker();
            runVampireBloodScent();
            runAntiCampCheck();
        }, intervalTicks, intervalTicks);
    }

    private void startAmbianceTask() {
        stopAmbianceTask();

        int intervalTicks = Math.max(40, plugin.getConfig().getInt("event.horror.ambient.interval-ticks", 100));
        ambianceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) {
                return;
            }

            playAmbientMoments();
            sendThreatTitles();
        }, intervalTicks, intervalTicks);
    }

    private void playAmbientMoments() {
        boolean enabled = plugin.getConfig().getBoolean("event.horror.ambient.enabled", true);
        if (!enabled) {
            return;
        }

        boolean thunder = plugin.getConfig().getBoolean("event.horror.ambient.thunder", true);
        boolean bats = plugin.getConfig().getBoolean("event.horror.ambient.bats", true);

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }

            if (thunder && Math.random() < 0.20D) {
                hunter.playSound(hunter.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35f, 1.4f);
            }

            if (bats && Math.random() < 0.35D) {
                hunter.playSound(hunter.getLocation(), Sound.ENTITY_BAT_LOOP, 0.55f, 0.7f);
            }
        }

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) {
                continue;
            }

            if (bats && Math.random() < 0.35D) {
                vampire.playSound(vampire.getLocation(), Sound.ENTITY_BAT_AMBIENT, 0.45f, 0.55f);
            }
        }
    }

    private void sendThreatTitles() {
        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }

            Player nearest = findNearestVisibleVampire(hunter, 12.0D);
            if (nearest != null) {
                hunter.sendTitle("", "§4You are being hunted", 0, 15, 5);
            }
        }

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) {
                continue;
            }

            Player nearest = findNearestHunter(vampire, 18.0D);
            if (nearest != null) {
                vampire.sendActionBar("§5Prey nearby: §f" + nearest.getName());
            }
        }
    }

    private void runHunterTracker() {
        boolean enabled = plugin.getConfig().getBoolean("event.hunter-tracker.enabled", true);
        if (!enabled) {
            return;
        }

        double radius = Math.max(1.0D, plugin.getConfig().getDouble("event.hunter-tracker.radius", 30.0D));

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }

            double effectiveRadius = radius;
            if (getSelectedClass(hunterId) == RoleClass.HUNTER_TRACKER) {
                effectiveRadius += Math.max(10.0D, plugin.getConfig().getDouble("event.classes.hunter-tracker.bonus-radius", 20.0D));
            }

            Player nearest = findNearestVisibleVampire(hunter, effectiveRadius);
            if (nearest != null) {
                hunter.setCompassTarget(nearest.getLocation());
                sendHunterTrackerActionBar(hunter, nearest, effectiveRadius);
            } else {
                hunter.sendActionBar("§7No vampire in range.");
            }
        }
    }

    private void runVampireBloodScent() {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire-tracking.blood-scent.enabled", true);
        if (!enabled) {
            return;
        }

        long cooldownSeconds = Math.max(1L, plugin.getConfig().getLong("event.vampire-tracking.blood-scent.cooldown-seconds", 20L));
        long durationSeconds = Math.max(1L, plugin.getConfig().getLong("event.vampire-tracking.blood-scent.duration-seconds", 7L));
        double radius = Math.max(1.0D, plugin.getConfig().getDouble("event.vampire-tracking.blood-scent.radius", 150.0D));

        long now = System.currentTimeMillis();
        long elapsedSinceStart = Math.max(0L, now - eventStartMillis);

        if (elapsedSinceStart < cooldownSeconds * 1000L) {
            return;
        }

        long intervalMs = cooldownSeconds * 1000L;
        long activeWindowMs = durationSeconds * 1000L;
        long sinceFirstPulse = elapsedSinceStart - intervalMs;
        if (sinceFirstPulse < 0L) {
            return;
        }

        long cyclePosition = sinceFirstPulse % intervalMs;
        if (cyclePosition >= activeWindowMs) {
            return;
        }

        boolean useCompass = plugin.getConfig().getBoolean("event.vampire-tracking.blood-scent.use-compass", true);
        boolean useActionBar = plugin.getConfig().getBoolean("event.vampire-tracking.blood-scent.use-actionbar", true);

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) {
                continue;
            }

            Player nearest = findNearestHunter(vampire, radius);
            if (nearest == null) {
                if (useActionBar) {
                    vampire.sendActionBar("§7Blood scent found nothing nearby.");
                }
                continue;
            }

            double distance = vampire.getLocation().distance(nearest.getLocation());
            String direction = getDetailedRelativeDirection(vampire, nearest);

            if (useCompass) {
                vampire.setCompassTarget(nearest.getLocation());
            }

            vampire.playSound(vampire.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.45f, 0.5f);
            vampire.playSound(vampire.getLocation(), Sound.AMBIENT_CAVE.value(), 0.25f, 0.55f);

            if (useActionBar) {
                vampire.sendActionBar("§5Blood scent §8- §f" + nearest.getName() + " §d" +
                        (int) Math.round(distance) + "m §8(" + direction + ")");
            }

            vampire.sendTitle("", "§5Hunter revealed", 0, 12, 5);
        }
    }

    private void runAntiCampCheck() {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire-tracking.anti-camp.enabled", true);
        if (!enabled) {
            return;
        }

        long noMoveSeconds = Math.max(1L, plugin.getConfig().getLong("event.vampire-tracking.anti-camp.no-move-seconds", 10L));
        double requiredMovementBlocks = Math.max(0.1D, plugin.getConfig().getDouble("event.vampire-tracking.anti-camp.required-movement-blocks", 12.0D));
        int glowSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire-tracking.anti-camp.glow-seconds", 3));
        long revealCooldownSeconds = Math.max(1L, plugin.getConfig().getLong("event.vampire-tracking.anti-camp.reveal-cooldown-seconds", 20L));
        boolean useBossBarAlert = plugin.getConfig().getBoolean("event.vampire-tracking.anti-camp.use-bossbar-alert", true);
        boolean damageAtMaxStage = plugin.getConfig().getBoolean("event.vampire-tracking.anti-camp.damage-final-stage", true);

        long now = System.currentTimeMillis();

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }

            Location current = hunter.getLocation();
            Location reference = hunterCampReferenceLocations.get(hunterId);
            Long since = hunterCampReferenceMillis.get(hunterId);

            if (reference == null || since == null || current.getWorld() == null || reference.getWorld() == null
                    || !current.getWorld().equals(reference.getWorld())) {
                hunterCampReferenceLocations.put(hunterId, current.clone());
                hunterCampReferenceMillis.put(hunterId, now);
                hunterCampStage.put(hunterId, 0);
                continue;
            }

            double movedDistance = current.distance(reference);

            if (movedDistance >= requiredMovementBlocks) {
                hunterCampReferenceLocations.put(hunterId, current.clone());
                hunterCampReferenceMillis.put(hunterId, now);
                hunterCampStage.put(hunterId, 0);
                continue;
            }

            long stillForMs = now - since;
            int stage = hunterCampStage.getOrDefault(hunterId, 0);

            if (stillForMs >= noMoveSeconds * 1000L && stage < 1) {
                hunterCampStage.put(hunterId, 1);
                hunter.sendMessage(PREFIX + "§eMove soon or your position will be revealed.");
                hunter.sendTitle("", "§eMove now", 0, 20, 5);
            }

            if (stillForMs >= noMoveSeconds * 2000L && stage < 2) {
                hunterCampStage.put(hunterId, 2);
                hunter.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, false, false, false));
                hunter.sendMessage(PREFIX + "§cYou are glowing because you stayed still too long.");
            }

            if (stillForMs >= noMoveSeconds * 3000L && stage < 3) {
                long cooldownUntil = hunterCampRevealCooldownUntil.getOrDefault(hunterId, 0L);
                if (now >= cooldownUntil) {
                    hunterCampStage.put(hunterId, 3);
                    hunterCampRevealCooldownUntil.put(hunterId, now + (revealCooldownSeconds * 1000L));
                    revealStillHunterToVampires(hunter, glowSeconds, useBossBarAlert);
                }
            }

            if (stillForMs >= noMoveSeconds * 4000L && stage < 4) {
                hunterCampStage.put(hunterId, 4);
                if (damageAtMaxStage) {
                    double newHealth = Math.max(1.0D, hunter.getHealth() - 2.0D);
                    hunter.setHealth(newHealth);
                    hunter.sendMessage(PREFIX + "§4The darkness punishes your camping.");
                }
            }
        }
    }

    private void revealStillHunterToVampires(Player hunter, int glowSeconds, boolean useBossBarAlert) {
        hunter.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, false, false, false));

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) {
                continue;
            }

            if (!vampire.getWorld().equals(hunter.getWorld())) {
                continue;
            }

            double distance = vampire.getLocation().distance(hunter.getLocation());
            String direction = getDetailedRelativeDirection(vampire, hunter);

            vampire.sendMessage(PREFIX + "§4A still hunter is at §f" + (int) Math.round(distance)
                    + "§4 blocks §f" + direction + "§4.");
            vampire.sendTitle("", "§4Still Hunter Revealed", 0, 15, 5);

            if (useBossBarAlert) {
                showTemporaryAlertBossBar(vampire,
                        "§4Still hunter: §f" + (int) Math.round(distance) + "m §8(" + direction + ")",
                        Math.max(1L, glowSeconds),
                        BarColor.PURPLE);
            }
        }
    }

    private void startOpeningReveal() {
        if (openingRevealEndTask != null) {
            openingRevealEndTask.cancel();
            openingRevealEndTask = null;
        }

        boolean enabled = plugin.getConfig().getBoolean("event.vampire-tracking.opening-reveal.enabled", true);
        if (!enabled) {
            openingRevealEndsAt = 0L;
            return;
        }

        boolean glowAllHunters = plugin.getConfig().getBoolean("event.vampire-tracking.opening-reveal.glow-all-hunters", true);
        int durationSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire-tracking.opening-reveal.duration-seconds", 1));
        openingRevealEndsAt = System.currentTimeMillis() + (durationSeconds * 1000L);

        if (glowAllHunters) {
            for (UUID hunterId : new HashSet<>(hunters)) {
                Player hunter = Bukkit.getPlayer(hunterId);
                if (hunter != null && hunter.isOnline()) {
                    hunter.addPotionEffect(new PotionEffect(
                            PotionEffectType.GLOWING,
                            durationSeconds * 20,
                            0,
                            false,
                            false,
                            false
                    ));
                }
            }
        }

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire != null && vampire.isOnline()) {
                vampire.sendTitle("§5HUNT BEGINS", "§fHunters revealed for " + durationSeconds + "s", 0, 40, 10);
                vampire.sendMessage(PREFIX + "§5Hunters are revealed for the opening §f" + durationSeconds + "§5 seconds.");
            }
        }

        openingRevealEndTask = Bukkit.getScheduler().runTaskLater(plugin, () -> openingRevealEndsAt = 0L, durationSeconds * 20L);
    }

    private void sendHunterTrackerActionBar(Player hunter, Player nearest, double radius) {
        if (hunter == null || nearest == null) {
            return;
        }

        double distance = hunter.getLocation().distance(nearest.getLocation());
        String direction = getDetailedRelativeDirection(hunter, nearest);

        if (distance <= 5.0D) {
            hunter.sendActionBar("§cVampire very close §8- §f" + direction + " §8(" + (int) Math.round(distance) + "m)");
        } else if (distance <= radius) {
            hunter.sendActionBar("§bVampire nearby §8- §f" + direction + " §8(" + (int) Math.round(distance) + "m)");
        } else {
            hunter.sendActionBar("§7No vampire in range.");
        }

        if (distance <= 10.0D) {
            hunter.playSound(hunter.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT, 0.35f, 0.7f);
        }
    }

    private void applyCloseHeartbeat(Player hunter, Player vampire) {
        if (hunter == null || vampire == null || !hunter.isOnline() || !vampire.isOnline()) {
            return;
        }

        double distance = hunter.getLocation().distance(vampire.getLocation());
        if (distance > 10.0D) {
            return;
        }

        float pitch = distance <= 4.0D ? 0.55f : 0.85f;
        hunter.playSound(hunter.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT, 0.6f, pitch);
        hunter.sendActionBar("§4Heartbeat... something is near.");
    }

    private String getDetailedRelativeDirection(Player source, Player target) {
        double sourceYaw = normalizeYaw(source.getLocation().getYaw());
        double angleToTarget = Math.toDegrees(Math.atan2(
                target.getLocation().getZ() - source.getLocation().getZ(),
                target.getLocation().getX() - source.getLocation().getX()
        )) - 90.0D;

        double diff = normalizeYaw((float) (angleToTarget - sourceYaw));

        if (diff >= -22.5D && diff < 22.5D) return "ahead";
        if (diff >= 22.5D && diff < 67.5D) return "front-right";
        if (diff >= 67.5D && diff < 112.5D) return "right";
        if (diff >= 112.5D && diff < 157.5D) return "behind-right";
        if (diff >= 157.5D || diff < -157.5D) return "behind";
        if (diff >= -157.5D && diff < -112.5D) return "behind-left";
        if (diff >= -112.5D && diff < -67.5D) return "left";
        return "front-left";
    }

    private float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0F;
        if (normalized >= 180.0F) {
            normalized -= 360.0F;
        }
        if (normalized < -180.0F) {
            normalized += 360.0F;
        }
        return normalized;
    }

    private Player findNearestVisibleVampire(Player hunter, double radius) {
        Player nearest = null;
        double bestDistance = radius * radius;

        for (UUID vampireId : vampires) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) {
                continue;
            }

            if (!hunter.getWorld().equals(vampire.getWorld())) {
                continue;
            }

            double distance = hunter.getLocation().distanceSquared(vampire.getLocation());
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = vampire;
            }
        }

        return nearest;
    }

    private Player findNearestHunter(Player vampire, double radius) {
        Player nearest = null;
        double bestDistance = radius * radius;

        for (UUID hunterId : hunters) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) {
                continue;
            }

            if (!vampire.getWorld().equals(hunter.getWorld())) {
                continue;
            }

            double distance = vampire.getLocation().distanceSquared(hunter.getLocation());
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = hunter;
            }
        }

        return nearest;
    }

    private void revealNearbyVampires(Player source, double radius, int glowSeconds) {
        if (source == null || !source.isOnline()) {
            return;
        }

        boolean found = false;

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) {
                continue;
            }

            if (!source.getWorld().equals(vampire.getWorld())) {
                continue;
            }

            if (source.getLocation().distance(vampire.getLocation()) > radius) {
                continue;
            }

            found = true;
            vampire.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, false, false, false));
        }

        if (found) {
            source.playSound(source.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
        } else {
            source.playSound(source.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
        }
    }

    private void showTemporaryAlertBossBar(Player player, String title, long seconds, BarColor color) {
        if (player == null || !player.isOnline()) {
            return;
        }

        BossBar bar = Bukkit.createBossBar(title, color, BarStyle.SOLID);
        bar.setVisible(true);
        bar.setProgress(1.0D);
        bar.addPlayer(player);

        final int totalTicks = (int) Math.max(20L, seconds * 20L);
        final int[] elapsed = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline()) {
                bar.removeAll();
                task.cancel();
                return;
            }

            elapsed[0] += 10;
            double progress = Math.max(0.0D, 1.0D - ((double) elapsed[0] / totalTicks));
            bar.setProgress(progress);

            if (elapsed[0] >= totalTicks) {
                bar.removeAll();
                task.cancel();
            }
        }, 0L, 10L);
    }

    private void endEvent(EventWinner winner, boolean adminStopped) {
        phase = EventPhase.ENDING;

        Set<UUID> huntersSnapshot = new HashSet<>(hunters);
        Set<UUID> vampiresSnapshot = new HashSet<>(vampires);
        Set<UUID> spectatorsSnapshot = new HashSet<>(spectatorPlayers);
        Set<UUID> activeEligibleSnapshot = new HashSet<>(payoutEligibleActivePlayers);
        Set<UUID> spectatorEligibleSnapshot = new HashSet<>(payoutEligibleSpectators);

        cancelCountdown();
        stopTimer();
        stopTrackerTask();
        stopAmbianceTask();
        removeBossBar();

        if (openingRevealEndTask != null) {
            openingRevealEndTask.cancel();
            openingRevealEndTask = null;
        }

        Set<UUID> everyone = new HashSet<>();
        everyone.addAll(activePlayers);
        everyone.addAll(spectatorPlayers);
        everyone.addAll(disconnectedPlayers);

        if (!adminStopped) {
            rewardWinners(winner, huntersSnapshot, vampiresSnapshot, spectatorsSnapshot, activeEligibleSnapshot, spectatorEligibleSnapshot);
            updateWinnerStats(winner, huntersSnapshot, vampiresSnapshot);
            updateLoserStats(winner, huntersSnapshot, vampiresSnapshot);
        }

        announceRoundMVPs();

        for (UUID playerId : everyone) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                restorePlayer(player);
                teleportProtected(player, plugin.getEventArenaManager().getReturnSpawn());

                if (winner == EventWinner.HUNTERS) {
                    player.sendTitle("§bHunters Win!", "§7The night is held back", 0, 60, 10);
                } else if (winner == EventWinner.VAMPIRES) {
                    player.sendTitle("§5Vampires Win!", "§7The darkness consumes all", 0, 60, 10);
                } else if (adminStopped) {
                    player.sendTitle("§cEvent Stopped", "", 0, 60, 10);
                }
            } else {
                pendingReturnTeleport.add(playerId);
            }
        }

        if (adminStopped) {
            plugin.getChatManager().broadcastGlobal("§eThe Vampire Hunt event was stopped.");
        } else if (winner == EventWinner.HUNTERS) {
            plugin.getChatManager().broadcastGlobal("§bHunters won the Vampire Hunt!");
        } else if (winner == EventWinner.VAMPIRES) {
            plugin.getChatManager().broadcastGlobal("§5Vampires won the Vampire Hunt!");
        } else {
            plugin.getChatManager().broadcastGlobal("§7The Vampire Hunt ended.");
        }

        hardResetRuntimeState(true);
    }

    private void announceRoundMVPs() {
        Map<String, String> lines = new LinkedHashMap<>();

        UUID topKiller = getTopEntry(roundKills);
        UUID topInfector = getTopEntry(roundInfections);
        UUID lastSurvivor = getLongestAlivePlayer();
        UUID closestEscape = getClosestEscapePlayer();

        if (topKiller != null) {
            lines.put("Top Hunter/Vampire", getName(topKiller) + " §8(" + roundKills.getOrDefault(topKiller, 0) + " kills)");
        }

        if (topInfector != null) {
            lines.put("Most Infections", getName(topInfector) + " §8(" + roundInfections.getOrDefault(topInfector, 0) + ")");
        }

        if (lastSurvivor != null) {
            lines.put("Longest Survivor", getName(lastSurvivor));
        }

        if (closestEscape != null) {
            lines.put("Closest Escape", getName(closestEscape) + " §8(" + String.format(Locale.US, "%.1f", closestEscapeMeters.getOrDefault(closestEscape, 0.0D)) + "m)");
        }

        if (lines.isEmpty()) {
            return;
        }

        Bukkit.broadcastMessage("§8§m--------------------------------------------------");
        Bukkit.broadcastMessage("§6§lRound MVPs");
        for (Map.Entry<String, String> entry : lines.entrySet()) {
            Bukkit.broadcastMessage("§7" + entry.getKey() + ": §f" + entry.getValue());
        }
        Bukkit.broadcastMessage("§8§m--------------------------------------------------");
    }

    private UUID getTopEntry(Map<UUID, Integer> map) {
        UUID best = null;
        int bestValue = 0;

        for (Map.Entry<UUID, Integer> entry : map.entrySet()) {
            if (entry.getValue() > bestValue) {
                bestValue = entry.getValue();
                best = entry.getKey();
            }
        }

        return best;
    }

    private UUID getLongestAlivePlayer() {
        UUID best = null;
        long bestTime = -1L;
        long now = System.currentTimeMillis();

        Set<UUID> all = new HashSet<>();
        all.addAll(roundAliveStart.keySet());
        all.addAll(eliminationTime.keySet());

        for (UUID uuid : all) {
            long start = roundAliveStart.getOrDefault(uuid, eventStartMillis);
            long end = eliminationTime.getOrDefault(uuid, now);
            long alive = Math.max(0L, end - start);
            if (alive > bestTime) {
                bestTime = alive;
                best = uuid;
            }
        }

        return best;
    }

    private UUID getClosestEscapePlayer() {
        UUID best = null;
        double bestValue = Double.MAX_VALUE;

        for (Map.Entry<UUID, Double> entry : closestEscapeMeters.entrySet()) {
            if (entry.getValue() < bestValue) {
                bestValue = entry.getValue();
                best = entry.getKey();
            }
        }

        return best;
    }

    private String getName(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() == null ? uuid.toString() : player.getName();
    }

    private void rewardWinners(EventWinner winner,
                               Set<UUID> huntersSnapshot,
                               Set<UUID> vampiresSnapshot,
                               Set<UUID> spectatorsSnapshot,
                               Set<UUID> activeEligibleSnapshot,
                               Set<UUID> spectatorEligibleSnapshot) {
        if (winner == EventWinner.NONE) {
            return;
        }

        boolean economyEnabled = plugin.getConfig().getBoolean("event.rewards.economy.enabled", false);
        double rewardAmount = Math.max(0.0D, plugin.getConfig().getDouble("event.rewards.economy.amount", 0.0D));

        Set<UUID> winners = new HashSet<>();
        if (winner == EventWinner.HUNTERS) {
            for (UUID uuid : huntersSnapshot) {
                if (activeEligibleSnapshot.contains(uuid) && wasHunter(uuid)) {
                    winners.add(uuid);
                }
            }
            for (UUID uuid : spectatorsSnapshot) {
                if (spectatorEligibleSnapshot.contains(uuid) && wasHunter(uuid)) {
                    winners.add(uuid);
                }
            }
        } else if (winner == EventWinner.VAMPIRES) {
            for (UUID uuid : vampiresSnapshot) {
                if (activeEligibleSnapshot.contains(uuid) && wasVampire(uuid)) {
                    winners.add(uuid);
                }
            }
            for (UUID uuid : spectatorsSnapshot) {
                if (spectatorEligibleSnapshot.contains(uuid) && wasVampire(uuid)) {
                    winners.add(uuid);
                }
            }
        }

        String rewardText = economyEnabled && rewardAmount > 0.0D
                ? "§aYou won §6" + rewardAmount + " §acoins for your team victory!"
                : "§eYour team won, but no economy reward is enabled.";

        for (UUID uuid : winners) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);

            if (economyEnabled && rewardAmount > 0.0D) {
                EconomyResponse response = plugin.getEconomyService().deposit(offlinePlayer, rewardAmount);
                if (response == null || !response.transactionSuccess()) {
                    plugin.getLogger().warning("Failed to reward " + offlinePlayer.getName() + " with " + rewardAmount);
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        online.sendMessage(PREFIX + "§cYour team won, but Vault reward failed.");
                    }
                    continue;
                }
            }

            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                online.sendMessage(PREFIX + rewardText);
            }
        }
    }

    private void updateWinnerStats(EventWinner winner, Set<UUID> huntersSnapshot, Set<UUID> vampiresSnapshot) {
        if (winner == EventWinner.HUNTERS) {
            for (UUID uuid : huntersSnapshot) {
                if (wasHunter(uuid)) {
                    plugin.getEventStatsManager().addWin(uuid);
                    plugin.getEventStatsManager().addHunterWin(uuid);
                }
            }
            return;
        }

        if (winner == EventWinner.VAMPIRES) {
            for (UUID uuid : vampiresSnapshot) {
                if (wasVampire(uuid)) {
                    plugin.getEventStatsManager().addWin(uuid);
                    plugin.getEventStatsManager().addVampireWin(uuid);
                }
            }
        }
    }

    private void updateLoserStats(EventWinner winner, Set<UUID> huntersSnapshot, Set<UUID> vampiresSnapshot) {
        if (winner == EventWinner.HUNTERS) {
            for (UUID uuid : vampiresSnapshot) {
                if (wasVampire(uuid)) {
                    plugin.getEventStatsManager().addLoss(uuid);
                }
            }
            return;
        }

        if (winner == EventWinner.VAMPIRES) {
            for (UUID uuid : huntersSnapshot) {
                if (wasHunter(uuid)) {
                    plugin.getEventStatsManager().addLoss(uuid);
                }
            }
        }
    }

    private boolean wasVampire(UUID playerId) {
        return originalVampires.contains(playerId);
    }

    private boolean wasHunter(UUID playerId) {
        return originalHunters.contains(playerId);
    }

    private void checkCountdownViability() {
        if (phase != EventPhase.COUNTDOWN && phase != EventPhase.READY_CHECK) {
            return;
        }

        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) {
            cancelCountdown();
            phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
        }
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void stopTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    private void stopTrackerTask() {
        if (trackerTask != null) {
            trackerTask.cancel();
            trackerTask = null;
        }
    }

    private void stopAmbianceTask() {
        if (ambianceTask != null) {
            ambianceTask.cancel();
            ambianceTask = null;
        }
    }

    private void removeBossBar() {
        if (eventBossBar != null) {
            eventBossBar.removeAll();
            eventBossBar = null;
        }
    }

    private void cancelDisconnectTask(UUID playerId) {
        BukkitTask task = disconnectTimeoutTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private boolean teleportProtected(Player player, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) {
            return false;
        }

        protectedTeleportPlayers.add(player.getUniqueId());
        boolean success = player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);

        if (!success) {
            protectedTeleportPlayers.remove(player.getUniqueId());
        }

        return success;
    }

    private void savePlayerState(Player player) {
        savedStates.putIfAbsent(player.getUniqueId(), new PlayerSnapshot(player));
    }

    private void restorePlayer(Player player) {
        if (player == null) {
            return;
        }

        PlayerSnapshot snapshot = savedStates.remove(player.getUniqueId());
        if (snapshot == null) {
            clearEventState(player);
            return;
        }

        snapshot.restore(player);
    }

    private void preparePlayerForEvent(Player player, boolean clearInventory) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (clearInventory) {
            clearEventState(player);
        }

        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        player.setExp(0f);
        player.setLevel(0);

        Attribute maxHealthAttribute = Attribute.MAX_HEALTH;
        if (player.getAttribute(maxHealthAttribute) != null) {
            double maxHealth = player.getAttribute(maxHealthAttribute).getValue();
            player.setHealth(Math.min(maxHealth, 20.0D));
        } else {
            player.setHealth(20.0D);
        }

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        player.updateInventory();
    }

    private void clearEventState(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setExtraContents(null);
        inventory.setItemInOffHand(null);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        player.updateInventory();
    }

    private void giveRoleKit(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (isVampire(player.getUniqueId())) {
            giveConfiguredKit(player, "event.kits.vampire");
            applyVampireClassAdjustments(player);
        } else if (isHunter(player.getUniqueId())) {
            giveConfiguredKit(player, "event.kits.hunter");
            applyHunterClassAdjustments(player);
        }
    }

    private void applyVampireClassAdjustments(Player player) {
        RoleClass roleClass = getSelectedClass(player.getUniqueId());

        if (roleClass == RoleClass.VAMPIRE_BRUTE) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0, false, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false, true));
        } else if (roleClass == RoleClass.VAMPIRE_STALKER) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false, true));
        }

        applyPermanentVampireVision(player);
    }

    private void applyHunterClassAdjustments(Player player) {
        RoleClass roleClass = getSelectedClass(player.getUniqueId());

        if (roleClass == RoleClass.HUNTER_PRIEST) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, false, false, true));
        } else if (roleClass == RoleClass.HUNTER_TRACKER) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false, true));
        }
    }

    private void giveConfiguredKit(Player player, String path) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null || player == null || !player.isOnline()) {
            return;
        }

        clearEventState(player);
        loadArmor(player, section, path);
        loadInventoryItems(player, section, path);
        applyConfiguredPotionEffects(player, section, path);
    }

    private void loadArmor(Player player, ConfigurationSection section, String path) {
        PlayerInventory inv = player.getInventory();

        inv.setHelmet(adaptConfiguredItem(path, cloneItem(section.getItemStack("armor.helmet"))));
        inv.setChestplate(adaptConfiguredItem(path, cloneItem(section.getItemStack("armor.chestplate"))));
        inv.setLeggings(adaptConfiguredItem(path, cloneItem(section.getItemStack("armor.leggings"))));
        inv.setBoots(adaptConfiguredItem(path, cloneItem(section.getItemStack("armor.boots"))));
        inv.setItemInOffHand(adaptConfiguredItem(path, cloneItem(section.getItemStack("offhand"))));
    }

    private void loadInventoryItems(Player player, ConfigurationSection section, String path) {
        if (section.isList("items")) {
            List<?> rawItems = section.getList("items");
            if (rawItems != null) {
                for (Object raw : rawItems) {
                    if (raw instanceof ItemStack item) {
                        player.getInventory().addItem(adaptConfiguredItem(path, item.clone()));
                    }
                }
            }
        }

        if (section.isConfigurationSection("slots")) {
            ConfigurationSection slots = section.getConfigurationSection("slots");
            if (slots != null) {
                for (String key : slots.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(key);
                        ItemStack slotItem = adaptConfiguredItem(path, cloneItem(slots.getItemStack(key)));
                        if (slotItem != null && slot >= 0 && slot < player.getInventory().getSize()) {
                            player.getInventory().setItem(slot, slotItem);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        player.updateInventory();
    }

    private void applyConfiguredPotionEffects(Player player, ConfigurationSection section, String path) {
        if (section.isConfigurationSection("effects")) {
            ConfigurationSection effects = section.getConfigurationSection("effects");
            if (effects != null) {
                for (String effectName : effects.getKeys(false)) {
                    PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase(Locale.ROOT));
                    if (type == null) {
                        continue;
                    }

                    int amplifier = Math.max(0, effects.getInt(effectName + ".amplifier", 0));
                    if ("event.kits.vampire".equalsIgnoreCase(path) && type == PotionEffectType.SPEED) {
                        amplifier = Math.min(amplifier, getSelectedClass(player.getUniqueId()) == RoleClass.VAMPIRE_STALKER ? 1 : 0);
                    }

                    int durationSeconds = Math.max(1, effects.getInt(effectName + ".duration-seconds", 999999));
                    boolean ambient = effects.getBoolean(effectName + ".ambient", false);
                    boolean particles = effects.getBoolean(effectName + ".particles", false);
                    boolean icon = effects.getBoolean(effectName + ".icon", true);

                    player.addPotionEffect(new PotionEffect(
                            type,
                            durationSeconds * 20,
                            amplifier,
                            ambient,
                            particles,
                            icon
                    ));
                }
            }
            return;
        }

        applyBasicKitEffects(player, section, path);
    }

    private void applyBasicKitEffects(Player player, ConfigurationSection section, String path) {
        int speed = section.getInt("speed-level", -1);
        if (speed >= 0) {
            if ("event.kits.vampire".equalsIgnoreCase(path)) {
                speed = getSelectedClass(player.getUniqueId()) == RoleClass.VAMPIRE_STALKER ? 1 : 0;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, Math.max(0, speed), false, false, true));
        }

        int strength = section.getInt("strength-level", -1);
        if (strength >= 0) {
            if ("event.kits.vampire".equalsIgnoreCase(path) && getSelectedClass(player.getUniqueId()) == RoleClass.VAMPIRE_BRUTE) {
                strength = Math.max(strength, 0);
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, Math.max(0, strength), false, false, true));
        }

        int nightVision = section.getInt("night-vision-level", -1);
        if (nightVision >= 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, nightVision, false, false, true));
        }
    }

    private ItemStack adaptConfiguredItem(String path, ItemStack item) {
        if (item == null) {
            return null;
        }

        if (!"event.kits.hunter".equalsIgnoreCase(path)) {
            return item;
        }

        Material type = item.getType();
        if (type == Material.BOW) {
            return createConfiguredHunterBow();
        }

        if (type == Material.ARROW) {
            item.setAmount(Math.max(1, plugin.getConfig().getInt("event.hunter-bow.arrows", item.getAmount() <= 0 ? 24 : item.getAmount())));
            return item;
        }

        return item;
    }

    private ItemStack createConfiguredHunterBow() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("event.hunter-bow.material", "BOW"));
        if (material == null) {
            material = Material.BOW;
        }

        ItemStack bow = new ItemStack(material, 1);
        ItemMeta meta = bow.getItemMeta();
        if (meta == null) {
            return bow;
        }

        int power = Math.max(0, plugin.getConfig().getInt("event.hunter-bow.enchantments.power", 0));
        int punch = Math.max(0, plugin.getConfig().getInt("event.hunter-bow.enchantments.punch", 0));
        int flame = Math.max(0, plugin.getConfig().getInt("event.hunter-bow.enchantments.flame", 0));
        int infinity = Math.max(0, plugin.getConfig().getInt("event.hunter-bow.enchantments.infinity", 0));

        if (getSelectedClassForCurrentBuild() == RoleClass.HUNTER_TRACKER) {
            power += 1;
        }

        if (power > 0) meta.addEnchant(Enchantment.POWER, power, true);
        if (punch > 0) meta.addEnchant(Enchantment.PUNCH, punch, true);
        if (flame > 0) meta.addEnchant(Enchantment.FLAME, flame, true);
        if (infinity > 0) meta.addEnchant(Enchantment.INFINITY, infinity, true);

        bow.setItemMeta(meta);
        return bow;
    }

    private RoleClass buildingClassContext = RoleClass.NONE;

    private RoleClass getSelectedClassForCurrentBuild() {
        return buildingClassContext;
    }

    private void defaultClassIfMissing(UUID playerId, boolean vampire) {
        RoleClass current = selectedClasses.getOrDefault(playerId, RoleClass.NONE);

        if (vampire) {
            if (current != RoleClass.VAMPIRE_BRUTE && current != RoleClass.VAMPIRE_STALKER) {
                selectedClasses.put(playerId, RoleClass.VAMPIRE_STALKER);
            }
        } else {
            if (current != RoleClass.HUNTER_PRIEST && current != RoleClass.HUNTER_TRACKER) {
                selectedClasses.put(playerId, RoleClass.HUNTER_TRACKER);
            }
        }
    }

    private void hardResetRuntimeState(boolean clearQueued) {
        cancelCountdown();
        stopTimer();
        stopTrackerTask();
        stopAmbianceTask();
        removeBossBar();

        if (openingRevealEndTask != null) {
            openingRevealEndTask.cancel();
            openingRevealEndTask = null;
        }

        for (BukkitTask task : disconnectTimeoutTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        disconnectTimeoutTasks.clear();

        if (clearQueued) {
            queuedPlayers.clear();
            readyPlayers.clear();
        }

        activePlayers.clear();
        vampires.clear();
        hunters.clear();
        spectatorPlayers.clear();
        payoutEligibleSpectators.clear();
        payoutEligibleActivePlayers.clear();
        protectedTeleportPlayers.clear();
        originalVampires.clear();
        originalHunters.clear();
        disconnectedPlayers.clear();
        hunterCampReferenceLocations.clear();
        hunterCampReferenceMillis.clear();
        hunterCampStage.clear();
        hunterCampRevealCooldownUntil.clear();
        classAbilityCooldowns.clear();

        roundKills.clear();
        roundInfections.clear();
        roundAliveStart.clear();
        eliminationTime.clear();
        closestEscapeMeters.clear();

        eventEndMillis = 0L;
        configuredDurationSeconds = 0L;
        eventStartMillis = 0L;
        openingRevealEndsAt = 0L;
        readyCountdownSecondsLeft = -1;
        suddenDeathStartMillis = 0L;

        if (clearQueued) {
            phase = EventPhase.IDLE;
        }
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private String formatTime(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public enum EventPhase {
        IDLE,
        QUEUEING,
        READY_CHECK,
        COUNTDOWN,
        ACTIVE,
        SUDDEN_DEATH,
        ENDING
    }

    public enum EventWinner {
        NONE,
        HUNTERS,
        VAMPIRES
    }

    public enum CommandSource {
        PLAYER,
        CONSOLE
    }

    public enum RoleClass {
        NONE("None"),
        VAMPIRE_STALKER("Vampire Stalker"),
        VAMPIRE_BRUTE("Vampire Brute"),
        HUNTER_TRACKER("Hunter Tracker"),
        HUNTER_PRIEST("Hunter Priest");

        private final String displayName;

        RoleClass(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static RoleClass fromInput(String input) {
            if (input == null) {
                return null;
            }

            String normalized = input.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");

            return switch (normalized) {
                case "stalker", "vampirestalker" -> VAMPIRE_STALKER;
                case "brute", "vampirebrute" -> VAMPIRE_BRUTE;
                case "tracker", "huntertracker" -> HUNTER_TRACKER;
                case "priest", "hunterpriest" -> HUNTER_PRIEST;
                default -> null;
            };
        }
    }

    private static final class PlayerSnapshot {
        private final ItemStack[] contents;
        private final ItemStack[] armor;
        private final ItemStack[] extra;
        private final ItemStack offHand;
        private final Location location;
        private final GameMode gameMode;
        private final int foodLevel;
        private final float saturation;
        private final float exhaustion;
        private final double health;
        private final int fireTicks;
        private final float exp;
        private final int level;
        private final Set<PotionEffect> potionEffects;

        private PlayerSnapshot(Player player) {
            PlayerInventory inventory = player.getInventory();
            this.contents = inventory.getContents().clone();
            this.armor = inventory.getArmorContents().clone();
            this.extra = inventory.getExtraContents().clone();
            this.offHand = inventory.getItemInOffHand() == null ? null : inventory.getItemInOffHand().clone();
            this.location = player.getLocation().clone();
            this.gameMode = player.getGameMode();
            this.foodLevel = player.getFoodLevel();
            this.saturation = player.getSaturation();
            this.exhaustion = player.getExhaustion();
            this.health = player.getHealth();
            this.fireTicks = player.getFireTicks();
            this.exp = player.getExp();
            this.level = player.getLevel();
            this.potionEffects = new HashSet<>(player.getActivePotionEffects());
        }

        private void restore(Player player) {
            PlayerInventory inventory = player.getInventory();
            inventory.setContents(contents);
            inventory.setArmorContents(armor);
            inventory.setExtraContents(extra);
            inventory.setItemInOffHand(offHand);

            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            for (PotionEffect effect : potionEffects) {
                player.addPotionEffect(effect);
            }

            player.setGameMode(gameMode);
            player.setFoodLevel(foodLevel);
            player.setSaturation(saturation);
            player.setExhaustion(exhaustion);
            player.setFireTicks(fireTicks);
            player.setExp(exp);
            player.setLevel(level);

            Attribute maxHealthAttribute = Attribute.MAX_HEALTH;
            if (player.getAttribute(maxHealthAttribute) != null) {
                double maxHealth = player.getAttribute(maxHealthAttribute).getValue();
                player.setHealth(Math.min(maxHealth, Math.max(1.0D, health)));
            } else {
                player.setHealth(Math.max(1.0D, health));
            }

            if (location != null && location.getWorld() != null) {
                player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }

            player.updateInventory();
        }
    }
}