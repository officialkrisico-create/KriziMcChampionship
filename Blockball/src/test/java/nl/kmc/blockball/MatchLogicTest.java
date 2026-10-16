package nl.kmc.blockball;

import nl.kmc.blockball.MatchLogic.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchLogicTest {

    @Test
    void anAssistIsTheTeammateTouchBeforeTheScorer() {
        TouchTracker t = new TouchTracker();
        t.touch("anna", 0, 100, true);
        t.touch("bert", 0, 140, false);
        Credit c = t.credit(0, 150, 100);
        assertEquals("bert", c.scorer());
        assertEquals("anna", c.assist());
        assertFalse(c.ownGoal());
    }

    @Test
    void noAssistWhenAnOpponentTouchedItInBetween() {
        TouchTracker t = new TouchTracker();
        t.touch("anna", 0, 100, true);
        t.touch("zed", 1, 120, false);
        t.touch("bert", 0, 140, false);
        assertNull(t.credit(0, 150, 100).assist());
    }

    @Test
    void noAssistOutsideTheWindowOrFromTheSamePlayer() {
        TouchTracker t = new TouchTracker();
        t.touch("anna", 0, 10, true);
        t.touch("bert", 0, 500, false);
        assertNull(t.credit(0, 510, 100).assist());

        TouchTracker same = new TouchTracker();
        same.touch("bert", 0, 100, false);
        same.touch("bert", 0, 120, false);
        assertNull(same.credit(0, 130, 100).assist());
    }

    @Test
    void aGoalOffTheDefendersTouchIsAnOwnGoal() {
        TouchTracker t = new TouchTracker();
        t.touch("zed", 1, 100, false);
        Credit c = t.credit(0, 110, 100);        // the ball ended up in side 1's goal, attackers are side 0
        assertTrue(c.ownGoal());
        assertNull(c.scorer());
    }

    @Test
    void noTouchNoCredit() {
        Credit c = new TouchTracker().credit(0, 10, 100);
        assertNull(c.scorer());
        assertFalse(c.ownGoal());
    }

    @Test
    void theHigherScoreWinsAtTheWhistle() {
        MatchState m = new MatchState(0, true);
        assertTrue(m.goal(1));
        assertTrue(m.timeUp());
        assertEquals(1, m.winner());
        assertFalse(m.goal(0), "a goal after the final whistle does not count");
        assertEquals(1, m.goals[1]);
        assertEquals(0, m.goals[0]);
    }

    @Test
    void aDrawGoesToGoldenGoalOvertime() {
        MatchState m = new MatchState(0, true);
        m.goal(0);
        m.goal(1);
        assertFalse(m.timeUp());
        assertTrue(m.overtime());
        assertTrue(m.goal(1));
        assertTrue(m.finished());
        assertEquals(1, m.winner());
    }

    @Test
    void aDrawStaysADrawWithoutOvertimeOrWhenOvertimeRunsOut() {
        MatchState a = new MatchState(0, false);
        assertTrue(a.timeUp());
        assertTrue(a.draw());

        MatchState b = new MatchState(0, true);
        assertFalse(b.timeUp());
        assertTrue(b.timeUp());
        assertTrue(b.draw());
    }

    @Test
    void firstToTheMaximumWinsAtOnce() {
        MatchState m = new MatchState(3, false);
        m.goal(0); m.goal(0);
        assertFalse(m.finished());
        m.goal(0);
        assertTrue(m.finished());
        assertEquals(0, m.winner());
    }

    @Test
    void heatsAreSpreadOverWavesAndOddTeamsGetABye() {
        var waves = MatchLogic.plan(List.of("a", "b", "c", "d", "e", "f", "g", "h"), 4);
        assertEquals(1, waves.size());
        assertEquals(4, waves.get(0).size());

        var two = MatchLogic.plan(List.of("a", "b", "c", "d", "e", "f", "g", "h"), 2);
        assertEquals(2, two.size());

        var odd = MatchLogic.plan(List.of("a", "b", "c"), 4);
        assertEquals(1, odd.size());
        assertEquals(2, odd.get(0).size());
        assertTrue(odd.get(0).get(1).bye());
        assertEquals("c", odd.get(0).get(1).a());
    }

    @Test
    void theTableRanksOnPointsThenGoalDifferenceThenGoals() {
        Table t = new Table();
        t.record("a", 300, 3, 1, true);
        t.record("b", 300, 2, 1, true);
        t.record("c", 150, 1, 3, false);
        t.record("d", 150, 1, 2, false);
        assertEquals(List.of("a", "b", "d", "c"), t.ranking());
    }
}
