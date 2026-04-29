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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        if (allQueuedPlayersReady() && phase == EventPhase.READY_CHECK) {
            plugin.getChatManager().broadcastToParticipants("§aAll players are ready.");
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
        if (phase == EventPhase.COUNTDOWN) {
            cancelCountdown();
            phase = EventPhase.READY_CHECK;
            plugin.getChatManager().broadcastToParticipants("§cCountdown paused because someone unreadied.");
        }
        return true;
    }

    public boolean startEventByAdmin(CommandSource source) {
        if (phase == EventPhase.ACTIVE || phase == EventPhase.COUNTDOWN || phase == EventPhase.SUDDEN_DEATH) return false;
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) return false;
        if (plugin.getConfig().getBoolean("event.ready-check.enabled", true)) readyPlayers.addAll(queuedPlayers);
        startCountdown(3);
        return true;
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
            if (queuedPlayers.isEmpty() && (phase == EventPhase.QUEUEING || phase == EventPhase.READY_CHECK)) {
                phase = EventPhase.IDLE;
            }
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

        eliminatePlayer(victimId, true, false,
                isVampire(victimId) ? "§cYou were slain and removed from combat." : "§cYou were eliminated from the event.");
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
            if (getSelectedClass(attackerId) == RoleClass.HUNTER_PRIEST) revealNearbyVampires(attacker, 20.0D, 3);
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

        if (plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.notify-hunter", true)) {
            victim.sendMessage(PREFIX + "§5A vampire attack made you dizzy for §f" + seconds + "§5 seconds.");
        }
        if (plugin.getConfig().getBoolean("event.vampire.attack-effects.dizziness.notify-vampire", false)) {
            attacker.sendMessage(PREFIX + "§dYou inflicted dizziness on §f" + victim.getName() + "§d.");
        }
    }

    private void applyHunterDarknessPulse(Player hunter) {
        boolean enabled = plugin.getConfig().getBoolean("event.horror.darkness-pulses.enabled", true);
        if (!enabled || hunter == null || !hunter.isOnline() || !isHunter(hunter.getUniqueId())) return;

        int darknessSeconds = Math.max(1, plugin.getConfig().getInt("event.horror.darkness-pulses.seconds", 2));
        int darknessAmplifier = Math.max(0, plugin.getConfig().getInt("event.horror.darkness-pulses.amplifier", 0));
        int slownessSeconds = Math.max(0, plugin.getConfig().getInt("event.horror.darkness-pulses.slowness-seconds", 1));

        hunter.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darknessSeconds * 20, darknessAmplifier, false, false, false));
        if (slownessSeconds > 0) {
            hunter.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slownessSeconds * 20, 0, false, false, false));
        }
        hunter.playSound(hunter.getLocation(), Sound.AMBIENT_CAVE, 0.6f, 0.8f);
        hunter.sendTitle("", "§8The darkness closes in...", 0, 20, 5);
    }

    public boolean isEventOngoing() {
        return phase == EventPhase.READY_CHECK || phase == EventPhase.COUNTDOWN
                || phase == EventPhase.ACTIVE || phase == EventPhase.SUDDEN_DEATH || phase == EventPhase.ENDING;
    }

    public boolean isEventParticipant(UUID id) {
        return queuedPlayers.contains(id) || activePlayers.contains(id) || spectatorPlayers.contains(id) || disconnectedPlayers.contains(id);
    }

    public boolean isActiveParticipant(UUID id) {
        return activePlayers.contains(id);
    }

    public boolean isVampire(UUID id) {
        return vampires.contains(id);
    }

    public boolean isHunter(UUID id) {
        return hunters.contains(id);
    }

    public boolean isSpectatorParticipant(UUID id) {
        return spectatorPlayers.contains(id);
    }

    public boolean isSpectatorPayoutEligible(UUID id) {
        return payoutEligibleSpectators.contains(id) || payoutEligibleActivePlayers.contains(id);
    }

    public void markSpectatorPayoutIneligible(UUID id) {
        payoutEligibleSpectators.remove(id);
        payoutEligibleActivePlayers.remove(id);
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

    public RoleClass getSelectedClass(UUID id) {
        return selectedClasses.getOrDefault(id, RoleClass.NONE);
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
        return fireAutoAbility(player, roleClass, now);
    }

    public Player getNextSpectatorTarget(Player spectator, boolean vampiresOnly, boolean huntersOnly) {
        if (spectator == null || !spectatorPlayers.contains(spectator.getUniqueId())) return null;
        List<Player> candidates = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            if (vampiresOnly && !vampires.contains(uuid)) continue;
            if (huntersOnly && !hunters.contains(uuid)) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) candidates.add(p);
        }
        if (candidates.isEmpty()) return null;
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
        if (!activePlayers.contains(playerId) && !spectatorPlayers.contains(playerId) && !queuedPlayers.contains(playerId)) return false;
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
        return (vampires.contains(first) && vampires.contains(second)) || (hunters.contains(first) && hunters.contains(second));
    }

    public void giveTracker(Player player) {
        if (player == null || !player.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.hunter-tracker.enabled", true);
        if (!enabled || !isHunter(player.getUniqueId())) return;
        // HUD-only tracker by design.
    }

    private void beginReadyCheckIfPossible() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers) return;

        boolean enabled = plugin.getConfig().getBoolean("event.ready-check.enabled", true);
        if (!enabled) {
            startCountdown();
            return;
        }

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
                if (allQueuedPlayersReady()) {
                    cancelCountdown();
                    startCountdown();
                    return;
                }
                if (readyCountdownSecondsLeft == 25 || readyCountdownSecondsLeft % 5 == 0 || readyCountdownSecondsLeft <= 5) {
                    for (UUID uuid : queuedPlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            p.sendTitle("§6Ready Check", "§fUse /vhunt ready §7(" + readyPlayers.size() + "/" + queuedPlayers.size() + ")", 0, 20, 5);
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
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
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.sendTitle("§4Vampire Hunt", "§7Starting in §f" + timeLeft[0], 0, 20, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
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
        VoteManager.RoundModifier modifier = voteManager.getActiveModifier();
        voteManager.reset();

        phase = EventPhase.ACTIVE;
        eventStartMillis = System.currentTimeMillis();

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
                player.sendTitle("§bHUNTER", "§7Kill all vampires", 0, 60, 10);
                hunterCampReferenceLocations.put(playerId, player.getLocation().clone());
                hunterCampReferenceMillis.put(playerId, System.currentTimeMillis());
                hunterCampStage.put(playerId, 0);
            }
        }

        configuredDurationSeconds = Math.max(30L, plugin.getConfig().getLong("event.duration-seconds", 600L));
        if (modifier == VoteManager.RoundModifier.SUDDEN_DEATH) {
            configuredDurationSeconds = Math.max(30L, configuredDurationSeconds / 2);
            plugin.getChatManager().broadcastToParticipants("§6[Modifier] §eSudden Death — timer halved!");
        }

        eventEndMillis = System.currentTimeMillis() + (configuredDurationSeconds * 1000L);
        createBossBar(configuredDurationSeconds);
        startTrackerTask();
        startAmbianceTask();

        plugin.getChatManager().broadcastToParticipants("§8[§7Fog of War§8] §7No opening reveal this round.");
        plugin.getChatManager().broadcastGlobal("§cA Vampire Hunt event has started!");
    }

    private void safeSpawnParticle(World world, Particle particle, Location location, int count,
                                   double offsetX, double offsetY, double offsetZ, double extra) {
        if (world == null || particle == null || location == null) return;
        try {
            world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
        } catch (IllegalArgumentException ignored) {
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to spawn particle " + particle + ": " + ex.getMessage());
        }
    }

    private <T> void safeSpawnParticle(World world, Particle particle, Location location, int count,
                                       double offsetX, double offsetY, double offsetZ, double extra, T data) {
        if (world == null || particle == null || location == null) return;
        try {
            world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data);
        } catch (IllegalArgumentException ignored) {
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to spawn particle " + particle + " with data: " + ex.getMessage());
        }
    }

    private void spawnHolyAbilityParticles(Player player) {
        if (player == null || !player.isOnline()) return;
        Location loc = player.getLocation().clone().add(0, 1, 0);
        safeSpawnParticle(
                player.getWorld(),
                Particle.DUST,
                loc,
                30,
                0.3, 0.5, 0.3,
                0.0,
                new Particle.DustOptions(Color.fromRGB(120, 200, 255), 1.0F)
        );
    }

    private void spawnHunterRevealParticles(Player player) {
        if (player == null || !player.isOnline()) return;
        Location loc = player.getLocation().clone().add(0, 1, 0);
        safeSpawnParticle(
                player.getWorld(),
                Particle.DUST,
                loc,
                20,
                0.25, 0.35, 0.25,
                0.0,
                new Particle.DustOptions(Color.fromRGB(80, 220, 255), 0.9F)
        );
    }

    private void spawnVampireRevealParticles(Player player) {
        if (player == null || !player.isOnline()) return;
        Location loc = player.getLocation().clone().add(0, 1, 0);
        safeSpawnParticle(
                player.getWorld(),
                Particle.DUST,
                loc,
                20,
                0.25, 0.35, 0.25,
                0.0,
                new Particle.DustOptions(Color.fromRGB(180, 60, 220), 0.9F)
        );
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

        String winnerLine;
        switch (winner) {
            case HUNTERS -> winnerLine = "§bHunters win! The night is safe.";
            case VAMPIRES -> winnerLine = "§5Vampires win! The hunt ends in darkness.";
            default -> winnerLine = "§7The event ended with no winner.";
        }
        plugin.getChatManager().broadcastGlobal(PREFIX + winnerLine);

        if (!forced) {
            distributePayouts(winner);
            voteManager.openVote();
        }

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
        UUID topVampire = null;
        int topVampireScore = -1;
        for (UUID id : originalVampires) {
            int score = roundKills.getOrDefault(id, 0) + roundInfections.getOrDefault(id, 0);
            if (score > topVampireScore) {
                topVampireScore = score;
                topVampire = id;
            }
        }

        UUID topHunter = null;
        int topHunterKills = -1;
        for (UUID id : originalHunters) {
            int kills = roundKills.getOrDefault(id, 0);
            if (kills > topHunterKills) {
                topHunterKills = kills;
                topHunter = id;
            }
        }

        final String vampireMvpName = topVampire != null ? resolveName(topVampire) : null;
        final String hunterMvpName = topHunter != null ? resolveName(topHunter) : null;
        final int finalVampireScore = topVampireScore;
        final int finalHunterKills = topHunterKills;

        Set<UUID> audience = new HashSet<>();
        audience.addAll(activePlayers);
        audience.addAll(spectatorPlayers);

        for (UUID id : audience) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;

            String subtitle;
            if (vampireMvpName != null && hunterMvpName != null) {
                subtitle = "§5" + vampireMvpName + " §8(" + finalVampireScore + " pts)  §8|  §b" + hunterMvpName + " §8(" + finalHunterKills + " kills)";
            } else if (vampireMvpName != null) {
                subtitle = "§5Vampire MVP: " + vampireMvpName + " §8(" + finalVampireScore + " pts)";
            } else if (hunterMvpName != null) {
                subtitle = "§bHunter MVP: " + hunterMvpName + " §8(" + finalHunterKills + " kills)";
            } else {
                subtitle = "§7No MVP this round.";
            }

            p.sendTitle("§6Round Over", subtitle, 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        }

        if (vampireMvpName != null) {
            plugin.getChatManager().broadcastToParticipants("§5Vampire MVP: §f" + vampireMvpName + " §8(kills+infections: " + finalVampireScore + ")");
        }
        if (hunterMvpName != null) {
            plugin.getChatManager().broadcastToParticipants("§bHunter MVP: §f" + hunterMvpName + " §8(kills: " + finalHunterKills + ")");
        }

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

        int seconds = Math.max(1, plugin.getConfig().getInt("event.vampire.start-invisibility.seconds", 15));
        if (getSelectedClass(vampire.getUniqueId()) == RoleClass.VAMPIRE_STALKER) {
            seconds += Math.max(5, plugin.getConfig().getInt("event.classes.vampire-stalker.bonus-invisibility-seconds", 15));
        }

        vampire.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, seconds * 20, 0, false, false, false));
        plugin.getChatManager().sendPrefixed(vampire, "§5You are invisible for the first §f" + seconds + "§5 seconds.");
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

        int startSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire.start-invisibility.seconds", 15));
        if (getSelectedClass(vampire.getUniqueId()) == RoleClass.VAMPIRE_STALKER) {
            startSeconds += Math.max(5, plugin.getConfig().getInt("event.classes.vampire-stalker.bonus-invisibility-seconds", 15));
        }

        long remainingStartInvisible = Math.max(0L, startSeconds - elapsedSeconds);
        if (remainingStartInvisible > 0L) {
            vampire.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) remainingStartInvisible * 20, 0, false, false, false));
        }
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

        if (particles) {
            world.spawnParticle(Particle.DUST, loc, 40, 0.35, 0.5, 0.35, 0.0, new Particle.DustOptions(Color.RED, 1.5f));
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
        if (spectatorEnabled) {
            spectatorPlayers.add(playerId);
            payoutEligibleSpectators.add(playerId);
            return;
        }

        pendingReturnTeleport.add(playerId);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            if (message != null && !message.isBlank()) online.sendMessage(PREFIX + message);
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
            if (message != null && !message.isBlank()) player.sendMessage(PREFIX + message);

            if (!leftEvent && spectatorEnabled && plugin.getEventArenaManager().getSpectatorSpawn() != null) {
                sendToSpectatorMode(player, true);
                return;
            }

            markSpectatorPayoutIneligible(playerId);
            if (eventBossBar != null) eventBossBar.removePlayer(player);
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
        player.sendMessage(PREFIX + "§eUse /vhunt specteleport next, hunters, or vampires.");
        player.sendMessage(PREFIX + "§eUse /vhunt teams to see live counts.");
        player.sendMessage(PREFIX + "§eUse /vhunt leave to leave spectator (loses payout eligibility).");
    }

    private void checkWinConditions() {
        if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;
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
        eventBossBar = Bukkit.createBossBar("§cTime Remaining: §f" + formatTime(totalSeconds), BarColor.RED, BarStyle.SOLID);
        eventBossBar.setVisible(true);
        addAllEligibleViewersToBossBar();

        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;

            long remainingMillis = Math.max(0L, eventEndMillis - System.currentTimeMillis());
            long remainingSeconds = remainingMillis / 1000L;
            double progress = configuredDurationSeconds <= 0L ? 0.0D :
                    Math.max(0.0D, Math.min(1.0D, (double) remainingSeconds / configuredDurationSeconds));

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
        if (phase == EventPhase.SUDDEN_DEATH) return;

        phase = EventPhase.SUDDEN_DEATH;
        suddenDeathStartMillis = System.currentTimeMillis();

        for (UUID id : new HashSet<>(hunters)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 10 * 20, 0, false, false, false));
                p.sendTitle("§4Blood Moon", "§cYou are revealed!", 0, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.8f);
            }
        }

        for (UUID id : new HashSet<>(vampires)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 12 * 20, 0, false, false, true));
                p.sendTitle("§4Blood Moon", "§5The hunt enters sudden death", 0, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.7f);
            }
        }

        plugin.getChatManager().broadcastGlobal("§4Blood Moon Sudden Death has begun!");
    }

    private void refreshBossBarPlayers() {
        if (eventBossBar == null) return;
        addAllEligibleViewersToBossBar();
        for (Player viewer : new ArrayList<>(eventBossBar.getPlayers())) {
            UUID id = viewer.getUniqueId();
            if (!activePlayers.contains(id) && !spectatorPlayers.contains(id)) {
                eventBossBar.removePlayer(viewer);
            }
        }
    }

    private void addAllEligibleViewersToBossBar() {
        if (eventBossBar == null) return;
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) eventBossBar.addPlayer(p);
        }
        for (UUID uuid : spectatorPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) eventBossBar.addPlayer(p);
        }
    }

    private void startTrackerTask() {
        stopTrackerTask();
        int intervalTicks = Math.max(10, plugin.getConfig().getInt("event.tracking.tick-interval", 20));

        trackerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;
            runHunterDirectionHUD();
            runVampireDirectionHUD();
            runAutoAbilities();
            runAntiCampCheck();
        }, intervalTicks, intervalTicks);
    }

    private String getDirectionLabel(Player viewer, Location target) {
        double dx = target.getX() - viewer.getLocation().getX();
        double dz = target.getZ() - viewer.getLocation().getZ();
        double targetAngle = Math.toDegrees(Math.atan2(-dx, dz));
        float playerYaw = viewer.getLocation().getYaw();
        double relative = targetAngle - playerYaw;

        while (relative > 180) relative -= 360;
        while (relative < -180) relative += 360;

        if (relative >= -30 && relative <= 30) return "§a▲ CENTER";
        if (relative > 30) return "§e▶ RIGHT";
        return "§e◀ LEFT";
    }

    private void runHunterDirectionHUD() {
        boolean enabled = plugin.getConfig().getBoolean("event.hunter-tracker.enabled", true);
        if (!enabled) return;

        double radius = plugin.getConfig().getDouble("event.hunter-tracker.radius", 30.0D);
        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) continue;

            Player nearest = findNearestActivePlayer(hunter, vampires, radius);
            if (nearest == null) {
                hunter.sendActionBar("§bVampire Sense §8| §7No vampires within " + (int) radius + " blocks");
            } else {
                int dist = (int) hunter.getLocation().distance(nearest.getLocation());
                String dir = getDirectionLabel(hunter, nearest.getLocation());
                hunter.sendActionBar("§bVampire Sense §8| " + dir + " §8| §c" + dist + " blocks");
            }
        }
    }

    private void runVampireDirectionHUD() {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire-tracking.blood-scent.use-actionbar", true);
        if (!enabled) return;

        double radius = plugin.getConfig().getDouble("event.vampire-tracking.blood-scent.radius", 150.0D);
        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) continue;

            Player nearest = findNearestActivePlayer(vampire, hunters, radius);
            if (nearest == null) {
                vampire.sendActionBar("§4❤ §cBlood Sense §8| §7No prey within " + (int) radius + " blocks");
            } else {
                int dist = (int) vampire.getLocation().distance(nearest.getLocation());
                String dir = getDirectionLabel(vampire, nearest.getLocation());
                vampire.sendActionBar("§4❤ §cBlood Sense §8| " + dir + " §8| §f" + dist + " blocks §4❤");
            }
        }
    }

    private void runAutoAbilities() {
        long now = System.currentTimeMillis();

        for (UUID playerId : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;

            try {
                RoleClass roleClass = getSelectedClass(playerId);
                if (roleClass == RoleClass.NONE) continue;

                long cooldownUntil = classAbilityCooldowns.getOrDefault(playerId, 0L);
                if (now < cooldownUntil) continue;

                fireAutoAbility(player, roleClass, now);
            } catch (Exception ex) {
                plugin.getLogger().warning("runAutoAbilities error for " + player.getName() + ": " + ex.getMessage());
                classAbilityCooldowns.put(playerId, now + 5000L);
            }
        }
    }

    private boolean fireAutoAbility(Player player, RoleClass roleClass, long now) {
        UUID playerId = player.getUniqueId();

        try {
            switch (roleClass) {
                case VAMPIRE_STALKER -> {
                    if (!isVampire(playerId)) return false;

                    int cooldown = plugin.getConfig().getInt("event.classes.vampire-stalker.auto-ability.cooldown-seconds", 30);
                    double range = plugin.getConfig().getDouble("event.classes.vampire-stalker.auto-ability.range", 40.0D);
                    int glowSeconds = plugin.getConfig().getInt("event.classes.vampire-stalker.auto-ability.glow-seconds", 10);

                    Player target = findNearestActivePlayer(player, hunters, range);
                    if (target != null) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, false, false, false));
                        spawnVampireRevealParticles(target);
                        player.sendActionBar("§5Stalker: §f" + target.getName() + " §5revealed through walls!");
                    }

                    classAbilityCooldowns.put(playerId, now + (cooldown * 1000L));
                    return true;
                }

                case VAMPIRE_BRUTE -> {
                    if (!isVampire(playerId)) return false;

                    int cooldown = plugin.getConfig().getInt("event.classes.vampire-brute.auto-ability.cooldown-seconds", 40);
                    int duration = plugin.getConfig().getInt("event.classes.vampire-brute.auto-ability.duration-seconds", 8);
                    int strengthAmp = plugin.getConfig().getInt("event.classes.vampire-brute.auto-ability.strength-amplifier", 1);
                    int speedAmp = plugin.getConfig().getInt("event.classes.vampire-brute.auto-ability.speed-amplifier", 0);

                    player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, duration * 20, strengthAmp, false, false, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration * 20, speedAmp, false, false, true));
                    safeSpawnParticle(player.getWorld(), Particle.CRIT, player.getLocation().clone().add(0, 1, 0), 18, 0.35, 0.45, 0.35, 0.02);
                    player.sendActionBar("§4Brute Rage: §fStrength + Speed for §c" + duration + "s");

                    classAbilityCooldowns.put(playerId, now + (cooldown * 1000L));
                    return true;
                }

                case HUNTER_TRACKER -> {
                    if (!isHunter(playerId)) return false;

                    int cooldown = plugin.getConfig().getInt("event.classes.hunter-tracker.auto-ability.cooldown-seconds", 30);
                    double range = plugin.getConfig().getDouble("event.classes.hunter-tracker.auto-ability.range", 40.0D);
                    int glowSeconds = plugin.getConfig().getInt("event.classes.hunter-tracker.auto-ability.glow-seconds", 10);

                    Player target = findNearestActivePlayer(player, vampires, range);
                    if (target != null) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, false, false, false));
                        spawnHunterRevealParticles(target);
                        player.sendActionBar("§bTracker: §f" + target.getName() + " §bmarked through walls!");
                    }

                    classAbilityCooldowns.put(playerId, now + (cooldown * 1000L));
                    return true;
                }

                case HUNTER_PRIEST -> {
                    if (!isHunter(playerId)) return false;

                    int cooldown = plugin.getConfig().getInt("event.classes.hunter-priest.auto-ability.cooldown-seconds", 35);
                    int duration = plugin.getConfig().getInt("event.classes.hunter-priest.auto-ability.duration-seconds", 8);
                    int speedAmp = plugin.getConfig().getInt("event.classes.hunter-priest.auto-ability.speed-amplifier", 1);
                    int resistanceAmp = plugin.getConfig().getInt("event.classes.hunter-priest.auto-ability.resistance-amplifier", 0);

                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration * 20, speedAmp, false, false, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, duration * 20, resistanceAmp, false, false, true));
                    spawnHolyAbilityParticles(player);
                    player.sendActionBar("§bHoly Blessing: §fSpeed + Resistance for §b" + duration + "s");

                    classAbilityCooldowns.put(playerId, now + (cooldown * 1000L));
                    return true;
                }

                default -> {
                    return false;
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Auto ability failed for " + player.getName() + " (" + roleClass + "): " + ex.getMessage());
            classAbilityCooldowns.put(playerId, now + 3000L);
            return false;
        }
    }

    private Player findNearestActivePlayer(Player from, Set<UUID> candidateSet, double maxRadius) {
        if (from == null || !from.isOnline()) return null;

        Player nearest = null;
        double nearestDistSq = maxRadius * maxRadius;

        for (UUID id : new HashSet<>(candidateSet)) {
            Player candidate = Bukkit.getPlayer(id);
            if (candidate == null || !candidate.isOnline()) continue;
            if (!from.getWorld().equals(candidate.getWorld())) continue;

            double distSq = from.getLocation().distanceSquared(candidate.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private Player findNearestVisibleVampire(Player hunter, double maxRadius) {
        return findNearestActivePlayer(hunter, vampires, maxRadius);
    }

    private void startAmbianceTask() {
        stopAmbianceTask();
        int intervalTicks = Math.max(40, plugin.getConfig().getInt("event.horror.ambient.interval-ticks", 100));
        ambianceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase != EventPhase.ACTIVE && phase != EventPhase.SUDDEN_DEATH) return;
            playAmbientMoments();
            sendThreatTitles();
        }, intervalTicks, intervalTicks);
    }

    private void playAmbientMoments() {
        boolean enabled = plugin.getConfig().getBoolean("event.horror.ambient.enabled", true);
        if (!enabled) return;

        boolean thunder = plugin.getConfig().getBoolean("event.horror.ambient.thunder", true);
        boolean bats = plugin.getConfig().getBoolean("event.horror.ambient.bats", true);

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) continue;

            if (thunder && Math.random() < 0.20D) {
                hunter.playSound(hunter.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.3f, 0.6f);
            }
            if (bats && Math.random() < 0.15D) {
                hunter.playSound(hunter.getLocation(), Sound.ENTITY_BAT_AMBIENT, 0.5f, 0.7f);
            }
        }
    }

    private void sendThreatTitles() {
        boolean enabled = plugin.getConfig().getBoolean("event.horror.threat-titles.enabled", true);
        if (!enabled) return;

        double proximityRadius = plugin.getConfig().getDouble("event.horror.threat-titles.proximity-radius", 12.0D);

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) continue;

            boolean vampireNearby = false;
            for (UUID vampireId : new HashSet<>(vampires)) {
                Player vampire = Bukkit.getPlayer(vampireId);
                if (vampire == null || !vampire.isOnline()) continue;

                if (hunter.getWorld().equals(vampire.getWorld()) &&
                        hunter.getLocation().distanceSquared(vampire.getLocation()) <= proximityRadius * proximityRadius) {
                    vampireNearby = true;
                    break;
                }
            }

            if (vampireNearby) applyCloseHeartbeat(hunter, null);
        }
    }

    private void applyCloseHeartbeat(Player hunter, Player vampire) {
        if (hunter == null || !hunter.isOnline()) return;
        boolean enabled = plugin.getConfig().getBoolean("event.horror.heartbeat.enabled", true);
        if (!enabled) return;
        hunter.playSound(hunter.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT, 0.9f, 1.0f);
    }

    private void runAntiCampCheck() {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire-tracking.anti-camp.enabled", true);
        if (!enabled) return;

        int noMoveSeconds = Math.max(5, plugin.getConfig().getInt("event.vampire-tracking.anti-camp.no-move-seconds", 10));
        double requiredBlocks = Math.max(1.0D, plugin.getConfig().getDouble("event.vampire-tracking.anti-camp.required-movement-blocks", 12.0D));
        int glowSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire-tracking.anti-camp.glow-seconds", 3));
        long revealCooldownMs = Math.max(1000L, plugin.getConfig().getLong("event.vampire-tracking.anti-camp.reveal-cooldown-seconds", 20) * 1000L);
        boolean useBossbarAlert = plugin.getConfig().getBoolean("event.vampire-tracking.anti-camp.use-bossbar-alert", true);
        boolean damageFinalStage = plugin.getConfig().getBoolean("event.vampire-tracking.anti-camp.damage-final-stage", true);

        long now = System.currentTimeMillis();

        for (UUID hunterId : new HashSet<>(hunters)) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) continue;

            Location ref = hunterCampReferenceLocations.getOrDefault(hunterId, hunter.getLocation().clone());
            long refTime = hunterCampReferenceMillis.getOrDefault(hunterId, now);
            int stage = hunterCampStage.getOrDefault(hunterId, 0);

            double moved = hunter.getLocation().distanceSquared(ref);
            if (moved >= requiredBlocks * requiredBlocks) {
                hunterCampReferenceLocations.put(hunterId, hunter.getLocation().clone());
                hunterCampReferenceMillis.put(hunterId, now);
                hunterCampStage.put(hunterId, 0);
                continue;
            }

            long elapsed = (now - refTime) / 1000L;
            if (elapsed < noMoveSeconds) continue;

            long cooldownUntil = hunterCampRevealCooldownUntil.getOrDefault(hunterId, 0L);
            if (now < cooldownUntil) continue;

            hunter.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowSeconds * 20, 0, false, false, false));
            if (useBossbarAlert && eventBossBar != null) {
                eventBossBar.setTitle("§4Anti-Camp! §c" + hunter.getName() + " is revealed!");
            }
            if (damageFinalStage && stage >= 2) {
                hunter.damage(2.0D);
            }

            hunterCampRevealCooldownUntil.put(hunterId, now + revealCooldownMs);
            hunterCampStage.merge(hunterId, 1, Integer::sum);
        }
    }

    private void revealNearbyVampires(Player hunter, double radius, int durationSeconds) {
        if (hunter == null || !hunter.isOnline()) return;

        for (UUID vampireId : new HashSet<>(vampires)) {
            Player vampire = Bukkit.getPlayer(vampireId);
            if (vampire == null || !vampire.isOnline()) continue;
            if (!hunter.getWorld().equals(vampire.getWorld())) continue;

            if (hunter.getLocation().distanceSquared(vampire.getLocation()) <= radius * radius) {
                vampire.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, durationSeconds * 20, 0, false, false, false));
                hunter.sendMessage(PREFIX + "§bYou revealed §f" + vampire.getName() + "§b!");
            }
        }
    }

    private void startOpeningReveal() {
        boolean enabled = plugin.getConfig().getBoolean("event.vampire-tracking.opening-reveal.enabled", true);
        if (!enabled) return;

        boolean glowAllHunters = plugin.getConfig().getBoolean("event.vampire-tracking.opening-reveal.glow-all-hunters", true);
        int durationSeconds = Math.max(1, plugin.getConfig().getInt("event.vampire-tracking.opening-reveal.duration-seconds", 1));

        openingRevealEndsAt = System.currentTimeMillis() + (durationSeconds * 1000L);

        if (glowAllHunters) {
            for (UUID hunterId : new HashSet<>(hunters)) {
                Player hunter = Bukkit.getPlayer(hunterId);
                if (hunter == null || !hunter.isOnline()) continue;
                hunter.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, durationSeconds * 20 + 5, 0, false, false, false));
            }
        }

        cancelOpeningReveal();
        openingRevealEndTask = Bukkit.getScheduler().runTaskLater(plugin, this::cancelOpeningReveal, durationSeconds * 20L + 5L);
    }

    private void cancelOpeningReveal() {
        if (openingRevealEndTask != null) {
            openingRevealEndTask.cancel();
            openingRevealEndTask = null;
        }
    }

    private void distributePayouts(EventWinner winner) {
        if (!plugin.getConfig().getBoolean("event.rewards.economy.enabled", false)) return;
        if (plugin.getVaultEconomy() == null) return;

        double amount = plugin.getConfig().getDouble("event.rewards.economy.amount", 250.0D);
        Set<UUID> winners = new HashSet<>();

        switch (winner) {
            case HUNTERS -> winners.addAll(originalHunters);
            case VAMPIRES -> winners.addAll(originalVampires);
            default -> {
                return;
            }
        }

        for (UUID winnerId : winners) {
            if (!isSpectatorPayoutEligible(winnerId) && !payoutEligibleActivePlayers.contains(winnerId)) continue;

            OfflinePlayer op = Bukkit.getOfflinePlayer(winnerId);
            EconomyResponse response = plugin.getVaultEconomy().depositPlayer(op, amount);
            Player online = Bukkit.getPlayer(winnerId);

            if (online != null && online.isOnline()) {
                if (response.transactionSuccess()) {
                    online.sendMessage(PREFIX + "§aYou earned §f$" + String.format("%.0f", amount) + "§a for winning the event!");
                } else {
                    online.sendMessage(PREFIX + "§cPayout failed: " + response.errorMessage);
                }
            }
        }
    }

    private void savePlayerState(Player player) {
        if (player == null || !player.isOnline()) return;

        PlayerInventory inv = player.getInventory();
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
                ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
                : 20.0;

        savedStates.put(player.getUniqueId(), new PlayerSnapshot(
                player.getLocation().clone(),
                player.getGameMode(),
                inv.getContents(),
                inv.getArmorContents(),
                inv.getItemInOffHand(),
                Math.min(player.getHealth(), maxHealth),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getActivePotionEffects(),
                player.getLevel(),
                player.getExp(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getFireTicks()
        ));
    }

    private void restorePlayer(Player player) {
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();
        PlayerSnapshot snapshot = savedStates.remove(playerId);

        if (snapshot == null) {
            player.getInventory().clear();
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
            }
            player.setFoodLevel(20);
            player.setSaturation(5.0f);
            player.setExp(0f);
            player.setLevel(0);
            player.setGameMode(GameMode.SURVIVAL);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setFireTicks(0);
            return;
        }

        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setContents(snapshot.getInventoryContents());
        inv.setArmorContents(snapshot.getArmorContents());
        inv.setItemInOffHand(snapshot.getOffHandItem());

        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        for (PotionEffect effect : snapshot.getPotionEffects()) {
            player.addPotionEffect(effect);
        }

        player.setGameMode(snapshot.getGameMode());

        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
                ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
                : 20.0;
        player.setHealth(Math.max(0.1, Math.min(snapshot.getHealth(), maxHealth)));

        player.setFoodLevel(snapshot.getFoodLevel());
        player.setSaturation(snapshot.getSaturation());
        player.setLevel(snapshot.getLevel());
        player.setExp(snapshot.getExp());
        player.setAllowFlight(snapshot.isAllowFlight());
        player.setFlying(snapshot.isFlying());
        player.setFireTicks(snapshot.getFireTicks());

        Location loc = snapshot.getLocation();
        if (loc != null && loc.getWorld() != null) {
            teleportProtected(player, loc);
        }
    }

    private void preparePlayerForEvent(Player player, boolean clearInventory) {
        if (player == null || !player.isOnline()) return;
        if (clearInventory) clearEventState(player);
        player.setGameMode(GameMode.ADVENTURE);
        if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            player.setHealth(player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
        }
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    private void clearEventState(Player player) {
        if (player == null || !player.isOnline()) return;
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
    }

    private void giveRoleKit(Player player) {
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();
        boolean vampireRole = isVampire(playerId);
        String section = vampireRole ? "event.kits.vampire" : "event.kits.hunter";
        ConfigurationSection kitSection = plugin.getConfig().getConfigurationSection(section);
        if (kitSection == null) return;

        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setItemInOffHand(null);

        ConfigurationSection armorSection = kitSection.getConfigurationSection("armor");
        if (armorSection != null) {
            applyArmorPiece(inv, armorSection, "helmet");
            applyArmorPiece(inv, armorSection, "chestplate");
            applyArmorPiece(inv, armorSection, "leggings");
            applyArmorPiece(inv, armorSection, "boots");
        }

        ConfigurationSection slotsSection = kitSection.getConfigurationSection("slots");
        if (slotsSection != null) {
            for (String key : slotsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    String raw = slotsSection.getString(key, "AIR:1");
                    String[] parts = raw.split(":");
                    Material mat = Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
                    int qty = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;

                    if (mat == Material.TOTEM_OF_UNDYING) continue;
                    inv.setItem(slot, new ItemStack(mat, qty));
                } catch (Exception ignored) {
                }
            }
        }

        ConfigurationSection effectsSection = kitSection.getConfigurationSection("effects");
        if (effectsSection != null) {
            for (String effectName : effectsSection.getKeys(false)) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase(Locale.ROOT));
                    if (type == null) continue;

                    if (!vampireRole && type == PotionEffectType.SPEED) {
                        continue;
                    }

                    ConfigurationSection ec = effectsSection.getConfigurationSection(effectName);
                    if (ec == null) continue;

                    int amplifier = ec.getInt("amplifier", 0);
                    int durationSeconds = ec.getInt("duration-seconds", 999999);
                    boolean ambient = ec.getBoolean("ambient", false);
                    boolean particles = ec.getBoolean("particles", false);
                    boolean icon = ec.getBoolean("icon", true);

                    player.addPotionEffect(new PotionEffect(type, durationSeconds * 20, amplifier, ambient, particles, icon));
                } catch (Exception ignored) {
                }
            }
        }

        inv.setItemInOffHand(null);

        if (vampireRole) {
            dyeLeatherArmorBlack(player);
        }
    }

    private void applyArmorPiece(PlayerInventory inv, ConfigurationSection armorSection, String pieceKey) {
        String materialName = armorSection.getString(pieceKey);
        if (materialName == null || materialName.equalsIgnoreCase("null") || materialName.isBlank()) return;

        try {
            Material mat = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            ItemStack piece = new ItemStack(mat);
            switch (pieceKey.toLowerCase(Locale.ROOT)) {
                case "helmet" -> inv.setHelmet(piece);
                case "chestplate" -> inv.setChestplate(piece);
                case "leggings" -> inv.setLeggings(piece);
                case "boots" -> inv.setBoots(piece);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void dyeLeatherArmorBlack(Player player) {
        if (player == null || !player.isOnline()) return;

        PlayerInventory inv = player.getInventory();
        ItemStack[] slots = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};

        for (ItemStack piece : slots) {
            if (piece == null) continue;
            if (piece.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta) {
                meta.setColor(Color.fromRGB(0, 0, 0));
                piece.setItemMeta(meta);
            }
        }

        inv.setHelmet(slots[0]);
        inv.setChestplate(slots[1]);
        inv.setLeggings(slots[2]);
        inv.setBoots(slots[3]);
    }

    private void defaultClassIfMissing(UUID playerId, boolean isVampire) {
        RoleClass current = selectedClasses.getOrDefault(playerId, RoleClass.NONE);
        if (current != RoleClass.NONE) return;
        selectedClasses.put(playerId, isVampire ? RoleClass.VAMPIRE_STALKER : RoleClass.HUNTER_TRACKER);
    }

    private void teleportProtected(Player player, Location destination) {
        if (player == null || !player.isOnline() || destination == null) return;
        protectedTeleportPlayers.add(player.getUniqueId());
        player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void checkCountdownViability() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (queuedPlayers.size() < minPlayers && phase == EventPhase.COUNTDOWN) {
            cancelCountdown();
            phase = queuedPlayers.isEmpty() ? EventPhase.IDLE : EventPhase.QUEUEING;
            plugin.getChatManager().broadcastToParticipants("§cCountdown cancelled: not enough players.");
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

    private void stopTimerTask() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    private void removeBossBar() {
        if (eventBossBar != null) {
            eventBossBar.removeAll();
            eventBossBar.setVisible(false);
            eventBossBar = null;
        }
    }

    private void cancelDisconnectTask(UUID playerId) {
        BukkitTask task = disconnectTimeoutTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    private void hardResetRuntimeState(boolean fullReset) {
        activePlayers.clear();
        vampires.clear();
        hunters.clear();
        spectatorPlayers.clear();
        disconnectedPlayers.clear();
        payoutEligibleActivePlayers.clear();
        payoutEligibleSpectators.clear();
        originalVampires.clear();
        originalHunters.clear();
        roundKills.clear();
        roundInfections.clear();
        roundAliveStart.clear();
        eliminationTime.clear();
        closestEscapeMeters.clear();
        classAbilityCooldowns.clear();
        hunterCampReferenceLocations.clear();
        hunterCampReferenceMillis.clear();
        hunterCampStage.clear();
        hunterCampRevealCooldownUntil.clear();
        openingRevealEndsAt = 0L;

        if (fullReset) {
            queuedPlayers.clear();
            readyPlayers.clear();
            selectedClasses.clear();
            pendingReturnTeleport.clear();

            for (BukkitTask task : disconnectTimeoutTasks.values()) task.cancel();
            disconnectTimeoutTasks.clear();
            savedStates.clear();

            eventEndMillis = 0L;
            configuredDurationSeconds = 0L;
            eventStartMillis = 0L;
            readyCountdownSecondsLeft = -1;
            suddenDeathStartMillis = 0L;
        }
    }

    public boolean adminForceVampire(UUID playerId) {
        if (!activePlayers.contains(playerId)) return false;
        hunters.remove(playerId);
        vampires.add(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            giveRoleKit(player);
            applyStartInvisibility(player);
            applyPermanentVampireVision(player);
            player.sendTitle("§5ROLE CHANGED", "§7You are now a vampire", 0, 40, 10);
        }
        return true;
    }

    public boolean adminForceHunter(UUID playerId) {
        if (!activePlayers.contains(playerId)) return false;
        vampires.remove(playerId);
        hunters.add(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            giveRoleKit(player);
            player.sendTitle("§bROLE CHANGED", "§7You are now a hunter", 0, 40, 10);
        }
        return true;
    }

    public String getAdminPlayerListMessage() {
        StringBuilder sb = new StringBuilder("§7Active players:\n");
        for (UUID id : activePlayers) {
            String name = resolveName(id);
            String role = vampires.contains(id) ? "§5Vampire" : hunters.contains(id) ? "§bHunter" : "§7Unknown";
            sb.append("  §f").append(name).append(" §8- ").append(role).append("\n");
        }
        return sb.toString().trim();
    }

    private String resolveName(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        return Bukkit.getOfflinePlayer(id).getName() != null
                ? Bukkit.getOfflinePlayer(id).getName()
                : id.toString().substring(0, 8);
    }

    private String formatTime(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
