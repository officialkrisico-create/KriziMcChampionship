package nl.kmc.tournament.recovery;

import nl.kmc.core.domain.TournamentPhase;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable point-in-time snapshot of tournament state used for emergency recovery.
 */
public record RecoverySnapshot(
        Instant      capturedAt,
        int          eventNumber,
        int          currentRound,
        int          totalRounds,
        TournamentPhase phase,
        String       activeGameId,
        List<String> playedGameIds,
        Map<String, Integer> teamPoints   // teamId → points
) {
    public RecoverySnapshot {
        playedGameIds = List.copyOf(playedGameIds);
        teamPoints    = Map.copyOf(teamPoints);
    }

    public boolean isStale(long maxAgeMillis) {
        return Instant.now().toEpochMilli() - capturedAt.toEpochMilli() > maxAgeMillis;
    }

    @Override public String toString() {
        return "RecoverySnapshot{event=" + eventNumber + " round=" + currentRound
               + "/" + totalRounds + " phase=" + phase
               + " capturedAt=" + capturedAt + "}";
    }
}
