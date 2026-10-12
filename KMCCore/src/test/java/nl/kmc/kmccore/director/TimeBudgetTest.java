package nl.kmc.kmccore.director;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeBudgetTest {

    private static TimeBudget.Params params(int games, int gpr, boolean finale, int midBreak, int readyChecks) {
        return new TimeBudget.Params(games, gpr,
                300,        // opening
                30,         // intermission
                30,         // voting
                10,         // prestart
                45,         // intro
                420,        // play
                40,         // post game
                15,         // round end
                midBreak, readyChecks, 25,
                finale, 600,
                75);
    }

    @Test
    void totalIsTheSumOfAllParts() {
        var est = TimeBudget.compute(params(12, 2, true, 300, 2));
        int perGame = 420 + (30 + 30 + 10 + 45 + 40);
        int expected = 300 + 12 * perGame + 6 * 15 + 300 + 2 * 25 + 600 + 75;
        assertEquals(expected, est.totalSeconds());
        assertEquals(6, est.rounds());
    }

    @Test
    void finaleAndMidBreakAreOptional() {
        var with = TimeBudget.compute(params(8, 2, true, 300, 1));
        var without = TimeBudget.compute(params(8, 2, false, 0, 0));
        assertEquals(600 + 300 + 25, with.totalSeconds() - without.totalSeconds());
    }

    @Test
    void midBreakIsIgnoredForASingleRound() {
        var one = TimeBudget.compute(params(2, 2, false, 300, 0));
        var none = TimeBudget.compute(params(2, 2, false, 0, 0));
        assertEquals(none.totalSeconds(), one.totalSeconds());
    }

    @Test
    void moreGamesTakeLonger() {
        assertTrue(TimeBudget.compute(params(10, 2, true, 300, 1)).totalSeconds()
                > TimeBudget.compute(params(6, 2, true, 300, 1)).totalSeconds());
    }

    @Test
    void formatsDurations() {
        assertEquals("45s", TimeBudget.format(45));
        assertEquals("1m 30s", TimeBudget.format(90));
        assertEquals("5m", TimeBudget.format(300));
        assertEquals("1u 15m", TimeBudget.format(4500));
        assertEquals("0s", TimeBudget.format(-5));
    }

    @Test
    void roundsFromGames() {
        assertEquals(6, PresetManager.roundsFor(12, 2));
        assertEquals(4, PresetManager.roundsFor(7, 2));
        assertEquals(1, PresetManager.roundsFor(1, 2));
        assertEquals(1, PresetManager.roundsFor(0, 0));
    }
}
