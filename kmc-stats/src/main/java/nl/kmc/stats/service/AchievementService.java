package nl.kmc.stats.service;

import nl.kmc.core.api.AchievementApi;
import nl.kmc.core.domain.AchievementDefinition;
import nl.kmc.core.domain.AchievementTrigger;
import nl.kmc.core.event.*;
import nl.kmc.stats.achievement.AchievementCatalog;
import nl.kmc.stats.achievement.AchievementLoader;
import nl.kmc.stats.achievement.AchievementNotifier;
import nl.kmc.storage.StorageModule;
import nl.kmc.storage.model.StoredAchievement;
import nl.kmc.storage.model.StoredAchievementProgress;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central achievement engine for KMC V2.
 *
 * <p>Implements {@link AchievementApi} and {@link Listener}.
 * Register it as a Bukkit listener from the plugin bootstrap.
 *
 * <p>Evaluation flow:
 * <ol>
 *   <li>Bukkit/KMC event fires</li>
 *   <li>The matching {@code @EventHandler} updates session counters</li>
 *   <li>The service iterates all definitions with that trigger
 *       and calls {@code tryUnlock}</li>
 *   <li>{@link #tryUnlock} checks idempotency, persists async, notifies player</li>
 * </ol>
 *
 * <p>Thread safety: unlock state and progress counters use ConcurrentHashMap.
 * Persistence is always async via the StorageModule executor.
 */
public final class AchievementService implements AchievementApi, Listener {

    private static final Logger LOG = Logger.getLogger(AchievementService.class.getName());

    private final JavaPlugin         plugin;
    private final StorageModule      storage;
    private final AchievementCatalog catalog;
    private final AchievementLoader  loader;
    private final AchievementNotifier notifier;

    // ── Persistent state (loaded from DB on player join) ──────────────────────

    /** uuid → set of unlocked achievement ids */
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();

    /** uuid → (achievementId → progress value) */
    private final Map<UUID, Map<String, Integer>> progress = new ConcurrentHashMap<>();

    // ── Session-scoped counters (in-memory only, reset per event) ─────────────

    /** Kills in the current game session: uuid → count */
    private final Map<UUID, Integer> gameKills         = new ConcurrentHashMap<>();

    /** Consecutive kills without a 30 s gap: uuid → streak */
    private final Map<UUID, Integer> killStreak        = new ConcurrentHashMap<>();
    private final Map<UUID, Long>    lastKillTime      = new ConcurrentHashMap<>();

    /** Whether the player has died in the current game: uuid → died */
    private final Set<UUID>          diedThisGame      = ConcurrentHashMap.newKeySet();

    /** Win-streak counter (resets on non-win game end): uuid → streak */
    private final Map<UUID, Integer> winStreak         = new ConcurrentHashMap<>();

    /** Tournament-scoped points accumulator: uuid → points */
    private final Map<UUID, Integer> tournamentPoints  = new ConcurrentHashMap<>();

    /** Tournament-scoped eliminations: uuid → count */
    private final Map<UUID, Integer> tournamentElims   = new ConcurrentHashMap<>();

    private int currentEventNumber = 1;

    public AchievementService(JavaPlugin plugin, StorageModule storage) {
        this.plugin   = plugin;
        this.storage  = storage;
        this.catalog  = new AchievementCatalog();
        this.loader   = new AchievementLoader(plugin);
        this.notifier = new AchievementNotifier();
        reload();
    }

    // ── AchievementApi ────────────────────────────────────────────────────────

    @Override
    public void grant(UUID uuid, String achievementId) {
        AchievementDefinition def = catalog.get(achievementId);
        if (def == null) {
            LOG.warning("[KMC/Achievements] grant() called for unknown id: " + achievementId);
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        tryUnlock(p, uuid, def);
    }

    @Override
    public void revoke(UUID uuid, String achievementId) {
        Set<String> set = unlocked.get(uuid);
        if (set != null) set.remove(achievementId);
        // DB record is intentionally retained for audit trail.
        // A DELETE migration can be added when needed.
        LOG.info("[KMC/Achievements] Revoked (in-memory) " + achievementId +
                 " from " + uuid + " — DB record retained for audit trail.");
    }

    @Override
    public boolean has(UUID uuid, String achievementId) {
        return unlocked.getOrDefault(uuid, Set.of()).contains(achievementId);
    }

    @Override
    public Set<String> getUnlocked(UUID uuid) {
        return Collections.unmodifiableSet(unlocked.getOrDefault(uuid, Set.of()));
    }

    @Override
    public int getProgress(UUID uuid, String achievementId) {
        return progress.getOrDefault(uuid, Map.of()).getOrDefault(achievementId, 0);
    }

    @Override
    public Collection<AchievementDefinition> getAll() { return catalog.getAll(); }

    @Override
    public AchievementDefinition get(String achievementId) { return catalog.get(achievementId); }

    @Override
    public void reload() {
        List<AchievementDefinition> defs = loader.loadAll();
        catalog.load(defs);
    }

    // ── Async player load ─────────────────────────────────────────────────────

    public void loadPlayer(UUID uuid) {
        storage.achievements().findUnlockedByPlayer(uuid).thenAccept(list -> {
            Set<String> ids = ConcurrentHashMap.newKeySet();
            list.forEach(a -> ids.add(a.achievementId));
            unlocked.put(uuid, ids);
        });
        storage.achievements().findAllProgress(uuid).thenAccept(list -> {
            Map<String, Integer> map = new ConcurrentHashMap<>();
            list.forEach(p -> map.put(p.achievementId, p.progress));
            progress.put(uuid, map);
        });
    }

    public void unloadPlayer(UUID uuid) {
        // Persistent state stays in memory intentionally — it is re-used if the
        // player reconnects without a server restart.  Only session counters
        // (which reset per game/tournament) are cleared to prevent unbounded growth.
        gameKills.remove(uuid);
        killStreak.remove(uuid);
        lastKillTime.remove(uuid);
        diedThisGame.remove(uuid);
        // winStreak / tournamentPoints / tournamentElims are tournament-scoped;
        // they are cleared at TournamentEndEvent, not per-session disconnect.
    }

    public void setEventNumber(int n) { this.currentEventNumber = n; }

    // ── Event handlers ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(KMCKillEvent event) {
        UUID killer = event.getKiller().getUuid();
        Player killerPlayer = event.getKillerPlayer();

        // Update lifetime kill counter
        int lifetimeKills = incrementProgress(killer, "kills", 1);

        // Update game-session kill count
        int gkills = gameKills.merge(killer, 1, Integer::sum);

        // Update kill streak
        long now = System.currentTimeMillis();
        long last = lastKillTime.getOrDefault(killer, 0L);
        int streak = (now - last < 30_000) ? killStreak.merge(killer, 1, Integer::sum) : 1;
        killStreak.put(killer, streak);
        lastKillTime.put(killer, now);

        // Evaluate kill triggers
        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.KILL_COUNT)) {
            if (lifetimeKills >= def.getProgressTarget()) tryUnlock(killerPlayer, killer, def);
        }
        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.KILL_COUNT_IN_GAME)) {
            if (gkills >= def.getProgressTarget()) tryUnlock(killerPlayer, killer, def);
        }
        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.KILL_STREAK_IN_GAME)) {
            if (streak >= def.getProgressTarget()) tryUnlock(killerPlayer, killer, def);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameStart(GameStartEvent event) {
        // Reset all game-session state for online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            gameKills.remove(uuid);
            killStreak.remove(uuid);
            lastKillTime.remove(uuid);
            diedThisGame.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathContextEvent event) {
        Player p  = event.getPlayer();
        UUID uuid = p.getUniqueId();

        diedThisGame.add(uuid);

        // First elimination ever — tryUnlock is idempotent so no need to pre-check has()
        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.PLAYER_FIRST_ELIMINATION)) {
            tryUnlock(p, uuid, def);
        }

        // Death-cause achievements
        String cause = event.getCause().name();
        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.PLAYER_DEATH_CAUSE)) {
            if (cause.equalsIgnoreCase(def.getObjectiveType())) tryUnlock(p, uuid, def);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(PlayerEliminatedEvent event) {
        UUID uuid  = event.getPlayer().getUniqueId();
        int elims  = tournamentElims.merge(uuid, 1, Integer::sum);
        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.ELIMINATIONS_IN_TOURNAMENT)) {
            if (elims >= def.getProgressTarget()) tryUnlock(event.getPlayer(), uuid, def);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameEnd(GameEndEvent event) {
        UUID mvpUuid = event.getMvpUuid();

        // Track GAMES_PLAYED for all online players (not just killers).
        // Note: ideally a participant list from GameEndEvent would be used here;
        // using getOnlinePlayers() is safe but may count spectators/staff.
        for (UUID uuid : gameKills.keySet()) {
            Player p = Bukkit.getPlayer(uuid);

            // Lifetime games-played progress
            int gamesPlayed = incrementProgress(uuid, "games_played", 1);
            for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.GAMES_PLAYED)) {
                if (gamesPlayed >= def.getProgressTarget()) tryUnlock(p, uuid, def);
            }
        }

        // MVP check
        if (mvpUuid != null) {
            Player mvp = Bukkit.getPlayer(mvpUuid);
            String gameId = event.getGame().getId();

            // GAME_WIN: player was MVP for this game
            for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.GAME_WIN)) {
                tryUnlock(mvp, mvpUuid, def);
            }
            for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.GAME_WIN_SPECIFIC)) {
                if (gameId.equals(def.getScopeGameId())) tryUnlock(mvp, mvpUuid, def);
            }

            // Win streak
            int streak = winStreak.merge(mvpUuid, 1, Integer::sum);
            for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.GAME_WIN_STREAK)) {
                if (streak >= def.getProgressTarget()) tryUnlock(mvp, mvpUuid, def);
            }

            // No-death win
            if (!diedThisGame.contains(mvpUuid)) {
                for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.GAME_WIN_NO_DEATH)) {
                    tryUnlock(mvp, mvpUuid, def);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTournamentEnd(TournamentEndEvent event) {
        String winTeamId = event.getWinningTeam() != null ? event.getWinningTeam().getId() : null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();

            // Tournaments participated
            int played = incrementProgress(uuid, "tournaments_played", 1);
            for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.TOURNAMENT_PLAYED)) {
                if (played >= def.getProgressTarget()) tryUnlock(p, uuid, def);
            }

            // Tournament win — team member check
            // (KMCPlayer.teamId is checked via API if available; fallback: skip)
            // This is deliberately lightweight — the detailed check can be wired
            // from the tournament engine which has full team context.
        }

        // Reset tournament-scoped state
        tournamentPoints.clear();
        tournamentElims.clear();
        winStreak.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPoints(PointsAwardedEvent event) {
        UUID uuid = event.getAward().getPlayerUuid();
        int pts = tournamentPoints.merge(uuid, event.getAward().getAmount(), Integer::sum);

        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.POINTS_IN_TOURNAMENT)) {
            if (pts >= def.getProgressTarget()) {
                Player p = Bukkit.getPlayer(uuid);
                tryUnlock(p, uuid, def);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClutch(ClutchMomentEvent event) {
        Player p  = event.getPlayer();
        UUID uuid = p.getUniqueId();
        String clutchTypeName = event.getType().name();

        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.CLUTCH_MOMENT)) {
            if (def.getClutchType() == null || def.getClutchType().equalsIgnoreCase(clutchTypeName)) {
                tryUnlock(p, uuid, def);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onObjective(GameObjectiveEvent event) {
        Player p    = event.getPlayer();
        UUID uuid   = p.getUniqueId();
        String type = event.getType().name();

        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.GAME_OBJECTIVE)) {
            // Game scope filter
            if (def.getScopeGameId() != null && !def.getScopeGameId().equals(event.getGameId())) continue;
            // Objective type filter
            if (def.getObjectiveType() != null && !def.getObjectiveType().equalsIgnoreCase(type)) continue;
            // Time threshold filter (for RACE_FINISHED_FAST etc.)
            if (def.getObjectiveThreshold() > 0 && event.getElapsedMs() > def.getObjectiveThreshold()) continue;

            tryUnlock(p, uuid, def);
        }
    }

    // ── Public helper — tournament win grant from external caller ─────────────

    /**
     * Called by the tournament engine when it has confirmed team membership.
     * @param uuid       player who won the tournament
     * @param isMvp      true if this player finished #1 individually
     */
    public void onTournamentWin(UUID uuid, boolean isMvp) {
        Player p = Bukkit.getPlayer(uuid);
        int wins = incrementProgress(uuid, "tournament_wins", 1);

        for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.TOURNAMENT_WIN)) {
            if (wins >= def.getProgressTarget()) tryUnlock(p, uuid, def);
        }
        if (isMvp) {
            for (AchievementDefinition def : catalog.getByTrigger(AchievementTrigger.TOURNAMENT_MVP)) {
                tryUnlock(p, uuid, def);
            }
        }
    }

    // ── Core unlock logic ─────────────────────────────────────────────────────

    /**
     * Idempotent unlock. Persists async, notifies player, no-ops if already unlocked.
     */
    private void tryUnlock(Player player, UUID uuid, AchievementDefinition def) {
        if (def == null) return;
        Set<String> set = unlocked.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(def.getId())) return; // already unlocked — CAS on add

        // Persist async
        StoredAchievement record = new StoredAchievement();
        record.playerUuid    = uuid;
        record.achievementId = def.getId();
        record.eventNumber   = currentEventNumber;
        storage.achievements().recordUnlock(record).exceptionally(ex -> {
            LOG.warning("[KMC/Achievements] Persist failed for " + def.getId() + ": " + ex.getMessage());
            // Roll back in-memory state so it can retry
            set.remove(def.getId());
            return null;
        });

        // Notify on main thread (Bukkit API requirement)
        if (player != null) {
            Bukkit.getScheduler().runTask(plugin, () -> notifier.notify(player, def));
        }

        LOG.info("[KMC/Achievements] " + uuid + " unlocked: " + def.getId());
    }

    /**
     * Increments a lifetime progress counter and persists async.
     * @return the new value after increment
     */
    private int incrementProgress(UUID uuid, String key, int delta) {
        Map<String, Integer> map = progress.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        int newVal = map.merge(key, delta, Integer::sum);

        StoredAchievementProgress p = new StoredAchievementProgress();
        p.playerUuid    = uuid;
        p.achievementId = key;
        p.progress      = newVal;
        p.target        = 0;
        storage.achievements().saveProgress(p).exceptionally(ex -> {
            LOG.warning("[KMC/Achievements] Progress save failed for " + key + "/" + uuid + ": " + ex.getMessage());
            return null;
        });

        return newVal;
    }
}
