package nl.kmc.game.api;

import nl.kmc.core.api.KMCApi;
import nl.kmc.core.api.KMCApiProvider;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.event.PlayerEliminatedEvent;
import nl.kmc.stats.model.GameStats;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Abstract base for all KMC V2 game managers.
 *
 * <p>Provides:
 * <ul>
 *   <li>Full FSM: IDLE → PREPARING → COUNTDOWN → GRACE → ACTIVE → DEATHMATCH → ENDED</li>
 *   <li>Scoreboard acquire/release lifecycle</li>
 *   <li>Reconnect snapshot capture and restoration</li>
 *   <li>Lazy listener registration (only during ACTIVE phase)</li>
 *   <li>Automatic GameResultEvent firing on game end</li>
 *   <li>Arena validation before start</li>
 * </ul>
 *
 * <p>Game plugins override only the abstract methods.
 */
public abstract class BaseGameManager implements Listener {

    protected final Logger             log;
    protected final JavaPlugin         plugin;
    protected final GameRegistration   registration;
    protected final KMCApi             api;
    protected final StatisticsService  statsService;

    private GameState state = GameState.IDLE;
    private int       countdownTask = -1;
    private int       graceTask     = -1;

    // Reconnect snapshots: uuid → snapshot, only populated during ACTIVE state
    private final Map<UUID, PlayerGameState> reconnectSnapshots = new HashMap<>();

    // Participants who joined this game instance
    private final List<UUID> participants = new ArrayList<>();

    protected BaseGameManager(JavaPlugin plugin, GameRegistration registration,
                               StatisticsService statsService) {
        this.plugin       = plugin;
        this.registration = registration;
        this.statsService = statsService;
        this.api          = KMCApiProvider.get();
        this.log          = Logger.getLogger("[KMC/" + registration.getId() + "]");
    }

    // ── Abstract contract ─────────────────────────────────────────────────────

    /**
     * Called in the {@code PREPARING} phase.
     * Implementations should load/validate the arena and teleport players to a
     * pre-game lobby area. The countdown starts automatically when this method returns.
     */
    protected abstract void onPrepare();

    /**
     * Called when the countdown reaches zero (transition {@code COUNTDOWN → GRACE}).
     * Implementations should lock inventories, apply starting kits, and freeze
     * player positions. PvP is still disabled during this phase.
     */
    protected abstract void onCountdownStart();

    /**
     * Called when the grace period expires (transition {@code GRACE → ACTIVE}).
     * PvP is now enabled. Implementations should start game-specific tasks
     * (timers, mob waves, etc.) here.
     */
    protected abstract void onGameStart();

    /**
     * Called when the game ends normally (state is already {@code ENDED}).
     * Implementations should compute the finish order and call
     * {@link #fireResult(String, UUID, String, java.util.List)} exactly once.
     * Do not call {@link #end()} from inside this method — it is already being
     * executed from within {@code end()}.
     */
    protected abstract void onGameEnd();

    /**
     * Captures game-specific mid-game state for reconnect recovery.
     * Called automatically when a participant disconnects during {@code ACTIVE} or {@code GRACE}.
     * Store game-specific data in {@link PlayerGameState#extra}.
     *
     * @param player the disconnecting player
     * @return populated snapshot, or {@code null} to skip reconnect for this player
     */
    protected abstract PlayerGameState capturePlayerState(Player player);

    /**
     * Restores a previously captured state when a player reconnects.
     * Only called if the snapshot has not expired (configurable via
     * {@code reconnect.timeout-seconds} in config). Implementations should
     * teleport the player, restore inventory, and re-apply game-specific state
     * from {@link PlayerGameState#extra}.
     *
     * @param player   the reconnecting player
     * @param snapshot the snapshot captured at disconnect time
     */
    protected abstract void restorePlayerState(Player player, PlayerGameState snapshot);

    /**
     * Returns the arena validator used by {@link #start()} before the game begins.
     * Typically delegated to the game's {@code ArenaManager}.
     */
    protected abstract ArenaValidator getArenaValidator();

    // ── FSM ───────────────────────────────────────────────────────────────────

    /**
     * Starts the game.
     * Runs arena validation, then transitions {@code IDLE → PREPARING → COUNTDOWN}.
     *
     * @return {@code true} if the game started successfully;
     *         {@code false} if the state is not {@code IDLE} or arena validation fails
     */
    public boolean start() {
        if (state != GameState.IDLE) {
            log.warning("Cannot start — current state is " + state);
            return false;
        }

        ValidationResult validation = getArenaValidator().validate();
        if (!validation.isValid()) {
            log.severe("Arena validation failed for " + registration.getId() + ":");
            validation.getErrors().forEach(e -> log.severe("  " + e));
            return false;
        }
        if (validation.hasWarnings()) {
            validation.getWarnings().forEach(w -> log.warning("  " + w));
        }

        transitionTo(GameState.PREPARING);
        api.games().acquireScoreboard(registration.getId());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        onPrepare();
        startCountdown();
        return true;
    }

    protected void startCountdown() {
        transitionTo(GameState.COUNTDOWN);
        int countdownSeconds = plugin.getConfig().getInt(
                "games." + registration.getId() + ".countdown", 10);

        int[] remaining = {countdownSeconds};
        countdownTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (remaining[0] <= 0) {
                cancelTask(countdownTask);
                countdownTask = -1;
                beginGrace();
                return;
            }
            if (remaining[0] <= 5 || remaining[0] == 10) {
                broadcastTitle("§e" + remaining[0], "§7Game starting...", 5, 15, 5);
            }
            remaining[0]--;
        }, 0L, 20L);
    }

    protected void beginGrace() {
        transitionTo(GameState.GRACE);
        onCountdownStart();

        // Collect participants
        participants.clear();
        plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> api.teams().getTeamByPlayer(p.getUniqueId()).isPresent())
                .map(Player::getUniqueId)
                .forEach(participants::add);

        // Start statistics tracking
        statsService.onGameStart(registration.getId(),
                api.games().getCurrentRound(), participants);

        int graceSeconds = plugin.getConfig().getInt(
                "games." + registration.getId() + ".grace-period", 15);
        graceTask = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            transitionTo(GameState.ACTIVE);
            onGameStart();
            graceTask = -1;
        }, graceSeconds * 20L);
    }

    /**
     * Ends the game immediately, regardless of current state.
     * Cancels any pending countdown/grace tasks, transitions to {@code ENDED},
     * finalises statistics, releases the scoreboard, unregisters listeners,
     * and calls {@link #onGameEnd()}.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public void end() {
        if (state == GameState.ENDED) return;
        cancelTask(countdownTask);
        cancelTask(graceTask);
        transitionTo(GameState.ENDED);
        statsService.onGameEnd();
        api.games().releaseScoreboard(registration.getId());
        HandlerList.unregisterAll(this);
        onGameEnd();
        log.info("Game ended.");
    }

    /**
     * Fires a {@link GameResultEvent} and signals the tournament engine that this game is over.
     * Call this exactly once from {@link #onGameEnd()} after computing final standings.
     *
     * @param winnerDescription human-readable winner label (e.g. "Team Red wins")
     * @param mvpUuid           UUID of the standout player, or {@code null}
     * @param mvpName           display name of the MVP, or {@code null}
     * @param finishOrder       UUIDs in placement order (1st at index 0)
     */
    protected void fireResult(String winnerDescription, UUID mvpUuid, String mvpName,
                              List<UUID> finishOrder) {
        List<GameStats> stats = statsService.getActiveStatsList();
        GameResultEvent event = new GameResultEvent(
                registration, winnerDescription, mvpUuid, mvpName, stats, finishOrder);
        plugin.getServer().getPluginManager().callEvent(event);

        // Signal tournament engine
        api.games().signalGameEnd(registration.getId(), winnerDescription);
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (state != GameState.ACTIVE && state != GameState.GRACE) return;
        Player p = event.getPlayer();
        if (!participants.contains(p.getUniqueId())) return;

        PlayerGameState snapshot = capturePlayerState(p);
        if (snapshot != null) {
            reconnectSnapshots.put(p.getUniqueId(), snapshot);
            log.info("Captured reconnect snapshot for " + p.getName());
        }
    }

    /**
     * Attempts to restore a player who just reconnected.
     * Called by the game plugin's {@code PlayerJoinEvent} handler.
     *
     * @param player the reconnecting player
     * @return {@code true} if a valid (non-expired) snapshot was found and restored;
     *         {@code false} if the player had no snapshot or it had expired
     */
    public boolean handleReconnect(Player player) {
        PlayerGameState snapshot = reconnectSnapshots.get(player.getUniqueId());
        if (snapshot == null) return false;

        long timeout = plugin.getConfig().getLong("reconnect.timeout-seconds", 300) * 1000L;
        if (snapshot.isExpired(timeout)) {
            reconnectSnapshots.remove(player.getUniqueId());
            log.info("Reconnect snapshot expired for " + player.getName());
            return false;
        }

        reconnectSnapshots.remove(player.getUniqueId());
        restorePlayerState(player, snapshot);
        log.info("Restored state for reconnecting player " + player.getName());
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    protected void transitionTo(GameState newState) {
        log.info("State: " + state + " → " + newState);
        this.state = newState;
    }

    private void cancelTask(int taskId) {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    protected void broadcastTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        participants.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .forEach(p -> p.sendTitle(title, subtitle, fadeIn, stay, fadeOut));
    }

    protected void broadcast(String message) {
        participants.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .forEach(p -> p.sendMessage(message));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public GameState         getState()          { return state; }
    public GameRegistration  getRegistration()   { return registration; }
    public List<UUID>        getParticipants()   { return Collections.unmodifiableList(participants); }
    public boolean           isRunning()         { return state.isRunning(); }
    public boolean           isIdle()            { return state.isIdle(); }
}
