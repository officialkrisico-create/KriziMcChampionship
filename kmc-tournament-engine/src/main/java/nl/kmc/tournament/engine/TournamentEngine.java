package nl.kmc.tournament.engine;

import nl.kmc.core.domain.TournamentPhase;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.TeamService;
import nl.kmc.core.service.TournamentService;
import nl.kmc.game.api.GameResultEvent;
import nl.kmc.tournament.recovery.TournamentRecoveryEngine;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Central tournament orchestrator.
 *
 * <p>Manages the full phase sequence defined in {@link TournamentPhase}.
 * Each phase is handled by a registered {@link TournamentPhaseHandler}.
 * When a handler calls {@link #advance()}, the engine transitions to the next phase
 * and hands control to that phase's handler.
 *
 * <p>The sequence per round is:
 * <pre>
 *   WAITING → READY_CHECK → OPENING_CEREMONY → TEAM_SHOWCASE
 *   → TOURNAMENT_OVERVIEW → GAME_LINEUP_SHOWCASE → VOTING_PHASE
 *   → GAME_INTRO → GAME_ACTIVE → GAME_END_CEREMONY
 *   → [more games? → VOTING_PHASE] → ROUND_END_CEREMONY
 *   → [more rounds? → TEAM_SHOWCASE] → CLOSING_CEREMONY → ENDED
 * </pre>
 */
public final class TournamentEngine implements Listener {

    private static final Logger LOG = Logger.getLogger(TournamentEngine.class.getName());

    private final JavaPlugin             plugin;
    private final TournamentService      tournament;
    private final TeamService            teams;
    private final GameRegistryService    registry;
    private final TournamentRecoveryEngine recovery;

    private final Map<TournamentPhase, TournamentPhaseHandler> handlers = new EnumMap<>(TournamentPhase.class);

    // Games remaining in the current round's rotation
    private final Deque<String> roundGameQueue = new ArrayDeque<>();
    private boolean paused = false;

    public TournamentEngine(JavaPlugin plugin,
                            TournamentService tournament,
                            TeamService teams,
                            GameRegistryService registry,
                            TournamentRecoveryEngine recovery) {
        this.plugin     = plugin;
        this.tournament = tournament;
        this.teams      = teams;
        this.registry   = registry;
        this.recovery   = recovery;
    }

    // ── Handler registration ──────────────────────────────────────────────────

    public void registerHandler(TournamentPhaseHandler handler) {
        handlers.put(handler.phase(), handler);
        LOG.fine("[KMC/Engine] Registered handler for phase: " + handler.phase());
    }

    // ── Lifecycle entry points ────────────────────────────────────────────────

    /** Called by the /kmc tournament start command. */
    public void startTournament() {
        if (tournament.isActive()) {
            LOG.warning("[KMC/Engine] Tournament already active.");
            return;
        }
        tournament.start();
        recovery.captureSnapshot("tournament-start");
        rebuildRoundQueue();
        transitionTo(TournamentPhase.READY_CHECK);
    }

    /** Called by the /kmc tournament end command or when all rounds complete. */
    public void endTournament() {
        if (!tournament.isActive()) return;
        recovery.captureSnapshot("tournament-end");
        transitionTo(TournamentPhase.CLOSING_CEREMONY);
    }

    /** Pause all automation — freezes at current phase. */
    public void pause() {
        paused = true;
        LOG.info("[KMC/Engine] Tournament PAUSED at phase " + tournament.getPhase());
        plugin.getServer().broadcastMessage("§c§l[KMC] Tournament paused.");
    }

    /** Resume from pause — re-enters the current phase handler. */
    public void resume() {
        if (!paused) return;
        paused = false;
        LOG.info("[KMC/Engine] Tournament RESUMED at phase " + tournament.getPhase());
        plugin.getServer().broadcastMessage("§a§l[KMC] Tournament resumed.");
        TournamentPhase current = tournament.getPhase();
        TournamentPhaseHandler handler = handlers.get(current);
        if (handler != null) handler.enter(this);
    }

    // ── Phase transition ──────────────────────────────────────────────────────

    /**
     * Transitions to the specified phase and enters its handler.
     * Called internally or by phase handlers when they complete.
     */
    public void transitionTo(TournamentPhase phase) {
        if (paused && phase != TournamentPhase.ENDED) {
            LOG.info("[KMC/Engine] Transition to " + phase + " deferred — engine is paused.");
            return;
        }
        LOG.info("[KMC/Engine] Phase: " + tournament.getPhase() + " → " + phase);
        tournament.setPhase(phase);

        TournamentPhaseHandler handler = handlers.get(phase);
        if (handler == null) {
            LOG.warning("[KMC/Engine] No handler for phase " + phase + " — auto-advancing.");
            advance();
            return;
        }
        handler.enter(this);
    }

    /**
     * Called by a phase handler to signal completion.
     * The engine computes the correct next phase based on tournament state.
     */
    public void advance() {
        TournamentPhase current = tournament.getPhase();
        TournamentPhase next = computeNextPhase(current);
        LOG.fine("[KMC/Engine] advance() from " + current + " → " + next);
        transitionTo(next);
    }

    /** Force-skips the current phase without waiting for its handler to finish. */
    public void skip() {
        TournamentPhase current = tournament.getPhase();
        TournamentPhaseHandler handler = handlers.get(current);
        if (handler != null) handler.skip(this);
        else advance();
    }

    // ── Next-phase logic ──────────────────────────────────────────────────────

    private TournamentPhase computeNextPhase(TournamentPhase current) {
        return switch (current) {
            case WAITING              -> TournamentPhase.READY_CHECK;
            case READY_CHECK          -> TournamentPhase.OPENING_CEREMONY;
            case OPENING_CEREMONY     -> TournamentPhase.TEAM_SHOWCASE;
            case TEAM_SHOWCASE        -> TournamentPhase.TOURNAMENT_OVERVIEW;
            case TOURNAMENT_OVERVIEW  -> TournamentPhase.GAME_LINEUP_SHOWCASE;
            case GAME_LINEUP_SHOWCASE -> TournamentPhase.VOTING_PHASE;
            case VOTING_PHASE         -> TournamentPhase.GAME_INTRO;
            case GAME_INTRO           -> TournamentPhase.GAME_ACTIVE;
            case GAME_ACTIVE          -> TournamentPhase.GAME_END_CEREMONY;
            case GAME_END_CEREMONY    -> nextAfterGameEnd();
            case ROUND_END_CEREMONY   -> nextAfterRoundEnd();
            case CLOSING_CEREMONY     -> TournamentPhase.ENDED;
            case ENDED                -> TournamentPhase.ENDED;
        };
    }

    private TournamentPhase nextAfterGameEnd() {
        // More games queued in this round?
        if (!roundGameQueue.isEmpty()) {
            return TournamentPhase.VOTING_PHASE;
        }
        // Round complete
        return TournamentPhase.ROUND_END_CEREMONY;
    }

    private TournamentPhase nextAfterRoundEnd() {
        int nextRound = tournament.getCurrentRound() + 1;
        if (nextRound > tournament.getTotalRounds()) {
            return TournamentPhase.CLOSING_CEREMONY;
        }
        tournament.advanceRound();
        rebuildRoundQueue();
        recovery.captureSnapshot("round-" + tournament.getCurrentRound() + "-start");
        // Return abbreviated pre-game flow for subsequent rounds
        return TournamentPhase.TEAM_SHOWCASE;
    }

    // ── Game queue helpers ────────────────────────────────────────────────────

    private void rebuildRoundQueue() {
        roundGameQueue.clear();
        registry.getUnplayed().forEach(g -> roundGameQueue.add(g.getId()));
        LOG.info("[KMC/Engine] Round queue rebuilt: " + roundGameQueue.size() + " games.");
    }

    public Optional<String> pollNextGame() {
        return roundGameQueue.isEmpty()
                ? Optional.empty()
                : Optional.of(roundGameQueue.poll());
    }

    public Deque<String> getRoundGameQueue() { return roundGameQueue; }

    // ── GameResultEvent listener ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameResult(GameResultEvent event) {
        LOG.info("[KMC/Engine] GameResultEvent received for: "
                 + event.getRegistration().getId()
                 + " — winner: " + event.getWinnerDescription());
        recovery.captureSnapshot("game-end-" + event.getRegistration().getId());
        // Advance from GAME_ACTIVE → GAME_END_CEREMONY
        if (tournament.getPhase() == TournamentPhase.GAME_ACTIVE) {
            advance();
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public JavaPlugin          getPlugin()     { return plugin; }
    public TournamentService   getTournament() { return tournament; }
    public TeamService         getTeams()      { return teams; }
    public GameRegistryService getRegistry()   { return registry; }
    public boolean             isPaused()      { return paused; }
}
