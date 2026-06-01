package nl.kmc.tournament.recovery;

import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.TeamService;
import nl.kmc.core.service.TournamentService;
import nl.kmc.tournament.engine.TournamentEngine;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Captures {@link RecoverySnapshot}s and restores tournament state from them.
 *
 * A snapshot is taken:
 *   – at tournament start
 *   – before and after every game transition
 *   – on a periodic interval (via {@link RecoveryScheduler})
 *
 * Recovery restores team points, the played-games list, the round number,
 * and then drops the engine back into the appropriate phase.
 */
public final class TournamentRecoveryEngine {

    private static final Logger LOG = Logger.getLogger(TournamentRecoveryEngine.class.getName());

    private final TournamentService  tournament;
    private final TeamService        teams;
    private final GameRegistryService registry;

    private RecoverySnapshot lastSnapshot;

    public TournamentRecoveryEngine(TournamentService tournament,
                                    TeamService teams,
                                    GameRegistryService registry) {
        this.tournament = tournament;
        this.teams      = teams;
        this.registry   = registry;
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    /** Capture with an optional label for logging/debugging. */
    public void captureSnapshot(String label) {
        LOG.fine("[KMC/Recovery] Capturing snapshot at checkpoint: " + label);
        capture();
    }

    public void capture() {
        Map<String, Integer> points = new HashMap<>();
        teams.getAllTeams().forEach(t -> points.put(t.getId(), t.getPoints()));

        String activeGameId = registry.getActive().map(r -> r.getId()).orElse(null);

        lastSnapshot = new RecoverySnapshot(
                Instant.now(),
                tournament.getEventNumber(),
                tournament.getCurrentRound(),
                tournament.getTotalRounds(),
                tournament.getPhase(),
                activeGameId,
                registry.getPlayedIds(),
                points
        );

        LOG.info("[KMC/Recovery] Snapshot captured: " + lastSnapshot);
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    /**
     * Restore from the last snapshot and transition the engine to the snapshot's phase.
     * Returns false if no snapshot is available.
     */
    public boolean restore(TournamentEngine engine) {
        if (lastSnapshot == null) {
            LOG.warning("[KMC/Recovery] No snapshot available to restore from.");
            return false;
        }
        LOG.info("[KMC/Recovery] Restoring from: " + lastSnapshot);

        // Restore team points
        lastSnapshot.teamPoints().forEach((teamId, pts) -> {
            teams.getTeam(teamId).ifPresent(t -> {
                int delta = pts - t.getPoints();
                if (delta != 0) t.addPoints(delta);
            });
        });

        // Restore played-game list
        registry.resetPlayedList();
        lastSnapshot.playedGameIds().forEach(registry::markPlayed);

        // Restore active game
        if (lastSnapshot.activeGameId() != null) {
            registry.setActive(lastSnapshot.activeGameId());
        } else {
            registry.clearActive();
        }

        // Transition engine to snapshot phase
        engine.transitionTo(lastSnapshot.phase());

        LOG.info("[KMC/Recovery] Restore complete. Phase: " + lastSnapshot.phase());
        return true;
    }

    public Optional<RecoverySnapshot> getLastSnapshot() {
        return Optional.ofNullable(lastSnapshot);
    }
}
