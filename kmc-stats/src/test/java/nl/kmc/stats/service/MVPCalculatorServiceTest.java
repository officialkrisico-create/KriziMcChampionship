package nl.kmc.stats.service;

import nl.kmc.stats.model.GameStats;
import nl.kmc.stats.model.MVPResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MVPCalculatorServiceTest {

    private MVPCalculatorService calculator;

    @BeforeEach
    void setUp() {
        // No config loading — default weights apply
        calculator = new MVPCalculatorService(null) {
            @Override public void loadConfig() { /* no-op: no plugin in unit tests */ }
        };
    }

    @Test
    void returns_empty_for_null_stats() {
        assertTrue(calculator.calculateGameMVP("skywars", null).isEmpty());
        assertTrue(calculator.calculateGameMVP("skywars", List.of()).isEmpty());
    }

    @Test
    void winner_is_mvp_when_stats_equal_except_win() {
        GameStats winner = makeStats(UUID.randomUUID(), "Alice", 5, 0, true, 200);
        GameStats loser  = makeStats(UUID.randomUUID(), "Bob",   5, 0, false, 200);

        var result = calculator.calculateGameMVP("skywars", List.of(winner, loser));
        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().playerName);
    }

    @Test
    void high_kill_player_beats_winner_with_no_kills() {
        GameStats highKiller = makeStats(UUID.randomUUID(), "Alice", 20, 0, false, 0);
        GameStats justWon    = makeStats(UUID.randomUUID(), "Bob",   0,  0, true, 0);

        // default: kills*2=40, won=50 → Bob should win barely (50 vs 40)
        var result = calculator.calculateGameMVP("test_game", List.of(highKiller, justWon));
        assertTrue(result.isPresent());
        assertEquals("Bob", result.get().playerName);
    }

    @Test
    void tournament_mvp_aggregates_across_games() {
        UUID alice = UUID.randomUUID();
        UUID bob   = UUID.randomUUID();

        GameStats g1alice = makeStats(alice, "Alice", 10, 0, true, 500);
        GameStats g1bob   = makeStats(bob,   "Bob",   2,  0, false, 100);
        GameStats g2alice = makeStats(alice, "Alice", 8,  0, true, 400);
        GameStats g2bob   = makeStats(bob,   "Bob",   15, 0, true, 300);

        var result = calculator.calculateTournamentMVP(List.of(g1alice, g1bob, g2alice, g2bob));
        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().playerName);
    }

    @Test
    void single_player_is_always_mvp() {
        GameStats solo = makeStats(UUID.randomUUID(), "Solo", 0, 0, true, 100);
        var result = calculator.calculateGameMVP("test", List.of(solo));
        assertTrue(result.isPresent());
        assertEquals("Solo", result.get().playerName);
        assertEquals(MVPResult.Scope.GAME, result.get().scope);
    }

    private GameStats makeStats(UUID uuid, String name, int kills, int deaths,
                                boolean won, int points) {
        GameStats gs = new GameStats();
        gs.playerUuid   = uuid;
        gs.playerName   = name;
        gs.kills        = kills;
        gs.deaths       = deaths;
        gs.won          = won;
        gs.pointsEarned = points;
        gs.gameId       = "test_game";
        gs.teamId       = "test_team";
        return gs;
    }
}
