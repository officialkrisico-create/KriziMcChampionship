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
    /**
     * Runs arena validation without starting the game.
     * Use this when {@link #start()} returns {@code false} to report exactly
     * what is missing.
     */
    public ValidationResult validateArena() {
        return getArenaValidator().validate();
    }

    /**
     * Player-friendly list of the reasons the arena isn't ready (errors only,
     * with the {@code [ERROR]} prefix stripped). Empty if the arena is valid.
     */
    public java.util.List<String> getArenaIssues() {
        return validateArena().getErrors().stream()
                .map(e -> e.replace("[ERROR] ", "").replace("[WARN]  ", ""))
                .toList();
    }

    /**
     * Sends a clear "arena not ready" message to a command sender, listing
     * exactly what's missing. Call this when {@link #start()} returns false.
     */
    public void reportArenaIssues(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("§cStart geweigerd — arena niet klaar:");
        java.util.List<String> issues = getArenaIssues();
        if (issues.isEmpty()) {
            sender.sendMessage("§7  (geen details beschikbaar — controleer de arena setup met §estatus§7)");
        } else {
            issues.forEach(i -> sender.sendMessage("§7  - §f" + i));
        }
    }

    // ── Per-game sidebar scoreboard (optional) ────────────────────────────────

    /** Sidebar title shown while this game owns the scoreboard. Override to customise. */
    protected String getScoreboardTitle() { return "§6§l" + registration.getDisplayName(); }

    /**
     * Per-game sidebar lines (top → bottom), rebuilt each scoreboard tick and
     * painted onto each player's board (KMC team colours preserved). Defaults to
     * a live tournament-standings board ({@link #defaultScoreboardLines}) so
     * every game shows something useful; override to add game-specific info
     * (kills, timers, objectives, …) — optionally re-using the default lines.
     */
    protected java.util.List<String> getScoreboardLines(Player viewer) {
        return defaultScoreboardLines(viewer);
    }

    /** Generic live board: phase, round, top teams, and the viewer's team. */
    protected final java.util.List<String> defaultScoreboardLines(Player viewer) {
        java.util.List<String> l = new java.util.ArrayList<>();
        try {
            java.util.UUID id = viewer.getUniqueId();
            l.add(api.tr(id, "game.board.phase", phaseLabel(viewer)));
            int round = api.games().getCurrentRound();
            if (round > 0) l.add(api.tr(id, "game.board.round", round));
            l.add("");
            l.add(api.tr(id, "game.board.top-teams"));
            java.util.List<nl.kmc.core.domain.KMCTeam> standings = api.teams().getStandings();
            for (int i = 0; i < Math.min(5, standings.size()); i++) {
                nl.kmc.core.domain.KMCTeam t = standings.get(i);
                l.add(" §7" + (i + 1) + ". " + t.getColor() + t.getDisplayName() + " §8- §e" + t.getPoints());
            }
            nl.kmc.core.domain.KMCTeam mine = api.teams().getTeamByPlayer(id).orElse(null);
            if (mine != null) {
                l.add("");
                l.add(api.tr(id, "game.board.your-team"));
                l.add(mine.getColor() + mine.getDisplayName() + " §8- §e" + mine.getPoints() + "p");
            }
        } catch (Throwable t) {
            return null; // never break the board on a transient API hiccup
        }
        return l;
    }

    /** Localised label for the current lifecycle phase, for sidebars. */
    protected final String phaseLabel(Player viewer) {
        java.util.UUID id = viewer.getUniqueId();
        return switch (state) {
            case IDLE       -> api.tr(id, "game.phase.idle");
            case PREPARING  -> api.tr(id, "game.phase.preparing");
            case COUNTDOWN  -> api.tr(id, "game.phase.countdown");
            case GRACE      -> api.tr(id, "game.phase.grace");
            case ACTIVE     -> api.tr(id, "game.phase.active");
            case DEATHMATCH -> api.tr(id, "game.phase.deathmatch");
            case ENDED      -> api.tr(id, "game.phase.ended");
        };
    }

    private void installScoreboard() {
        try {
            api.games().setScoreboard(registration.getId(), new nl.kmc.core.api.GameScoreboard() {
                @Override public String title(Player viewer) { return getScoreboardTitle(); }
                @Override public java.util.List<String> lines(Player viewer) { return getScoreboardLines(viewer); }
            });
        } catch (Throwable t) { log.warning("setScoreboard failed: " + t); }
    }

    public boolean start() {
        // Allow starting from IDLE or ENDED. Also recover from a stuck PREPARING
        // (a previous start whose onPrepare() threw) so a game can never get
        // permanently wedged.
        if (state != GameState.IDLE && state != GameState.ENDED && state != GameState.PREPARING) {
            log.warning("Cannot start — current state is " + state + " (use /<game> stop first).");
            return false;
        }
        if (state == GameState.PREPARING) abortToIdle();   // recover from a stuck prepare
        if (state == GameState.ENDED)     state = GameState.IDLE; // fresh start

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
        installScoreboard();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Guard prepare/countdown: if a game's onPrepare() throws, reset cleanly
        // to IDLE instead of leaving the game wedged in PREPARING — and log why.
        try {
            onPrepare();
            startCountdown();
        } catch (Throwable t) {
            log.severe("Game '" + registration.getId() + "' failed to start during prepare/countdown:");
            t.printStackTrace();
            abortToIdle();
            return false;
        }
        return true;
    }

    /** Cleans up a half-started game and returns to IDLE (no result fired). */
    private void abortToIdle() {
        cancelTask(countdownTask);
        cancelTask(graceTask);
        try { HandlerList.unregisterAll(this); }                       catch (Exception ignored) {}
        try { api.games().clearScoreboard(registration.getId()); }     catch (Exception ignored) {}
        try { api.games().releaseScoreboard(registration.getId()); }   catch (Exception ignored) {}
        state = GameState.IDLE;
        log.info("Game '" + registration.getId() + "' reset to IDLE.");
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
        try { statsService.onGameEnd(); }                            catch (Throwable t) { log.warning("statsService.onGameEnd failed: " + t); }
        try { api.games().clearScoreboard(registration.getId()); }   catch (Throwable t) { log.warning("clearScoreboard failed: " + t); }
        try { api.games().releaseScoreboard(registration.getId()); } catch (Throwable t) { log.warning("releaseScoreboard failed: " + t); }
        HandlerList.unregisterAll(this);
        try { onGameEnd(); }                                         catch (Throwable t) { log.severe("onGameEnd failed: " + t); t.printStackTrace(); }
        log.info("Game ended.");

        // Return to IDLE shortly after cleanup so the game can be started again.
        int resetTicks = Math.max(1, plugin.getConfig().getInt("post-game-reset-ticks", 100));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state == GameState.ENDED) {
                state = GameState.IDLE;
                log.info("Game reset to IDLE — ready to start again.");
            }
        }, resetTicks);
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
