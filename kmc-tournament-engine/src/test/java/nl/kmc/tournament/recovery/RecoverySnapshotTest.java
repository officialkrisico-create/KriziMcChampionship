package nl.kmc.tournament.recovery;

import nl.kmc.core.domain.TournamentPhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoverySnapshotTest {

    private RecoverySnapshot snapshot(Instant capturedAt) {
        return new RecoverySnapshot(
                capturedAt, 3, 2, 5,
                TournamentPhase.GAME_ACTIVE,
                "skywars",
                List.of("spleef", "tnt_tag"),
                Map.of("red", 300, "blue", 250)
        );
    }

    @Test
    void fields_are_stored_correctly() {
        Instant now = Instant.now();
        RecoverySnapshot s = snapshot(now);

        assertEquals(now, s.capturedAt());
        assertEquals(3, s.eventNumber());
        assertEquals(2, s.currentRound());
        assertEquals(5, s.totalRounds());
        assertEquals(TournamentPhase.GAME_ACTIVE, s.phase());
        assertEquals("skywars", s.activeGameId());
        assertEquals(List.of("spleef", "tnt_tag"), s.playedGameIds());
        assertEquals(300, s.teamPoints().get("red"));
        assertEquals(250, s.teamPoints().get("blue"));
    }

    @Test
    void played_game_ids_are_immutable() {
        RecoverySnapshot s = snapshot(Instant.now());
        assertThrows(UnsupportedOperationException.class,
                () -> s.playedGameIds().add("quake_craft"));
    }

    @Test
    void team_points_are_immutable() {
        RecoverySnapshot s = snapshot(Instant.now());
        assertThrows(UnsupportedOperationException.class,
                () -> s.teamPoints().put("green", 999));
    }

    @Test
    void mutable_inputs_are_defensively_copied() {
        List<String> games = new ArrayList<>(List.of("spleef"));
        Map<String, Integer> pts = new HashMap<>(Map.of("red", 100));

        RecoverySnapshot s = new RecoverySnapshot(
                Instant.now(), 1, 1, 5,
                TournamentPhase.VOTING_PHASE,
                "spleef", games, pts
        );

        games.add("tnt_tag");
        pts.put("blue", 999);

        assertEquals(1, s.playedGameIds().size());
        assertFalse(s.teamPoints().containsKey("blue"));
    }

    @Test
    void is_stale_returns_false_for_fresh_snapshot() {
        RecoverySnapshot s = snapshot(Instant.now());
        assertFalse(s.isStale(60_000));
    }

    @Test
    void is_stale_returns_true_for_old_snapshot() {
        Instant ancient = Instant.now().minusSeconds(120);
        RecoverySnapshot s = snapshot(ancient);
        assertTrue(s.isStale(60_000));
    }

    @Test
    void to_string_contains_event_round_and_phase() {
        RecoverySnapshot s = snapshot(Instant.now());
        String str = s.toString();
        assertTrue(str.contains("3"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("GAME_ACTIVE"));
    }
}
