package nl.kmc.blockball;

import nl.kmc.blockball.BallPhysics.Box;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KeeperLogicTest {

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    @Test
    void theKeeperRotatesSoEverybodyGetsATurn() {
        Map<UUID, Integer> stints = new HashMap<>();
        List<UUID> team = List.of(A, B, C);
        UUID first = KeeperLogic.pick(team, stints, 2);
        assertEquals(A, first);
        stints.merge(first, 1, Integer::sum);
        UUID second = KeeperLogic.pick(team, stints, 2);
        assertEquals(B, second);
        stints.merge(second, 1, Integer::sum);
        assertEquals(C, KeeperLogic.pick(team, stints, 2));
        stints.merge(C, 1, Integer::sum);
        assertEquals(A, KeeperLogic.pick(team, stints, 2), "after a full round the first one again");
    }

    @Test
    void aTooSmallTeamPlaysWithoutAKeeper() {
        assertNull(KeeperLogic.pick(List.of(A), new HashMap<>(), 2));
        assertNull(KeeperLogic.pick(List.of(), new HashMap<>(), 2));
        assertEquals(A, KeeperLogic.pick(List.of(A), new HashMap<>(), 1));
    }

    @Test
    void theZoneWidensTheGoalAndStaysInsideTheField() {
        Box field = new Box(0, 0, 0, 40, 6, 20);
        Box goal = new Box(0, 0, 8, 2, 4, 12);
        Box zone = KeeperLogic.zone(goal, field, 6);
        assertEquals(0, zone.minX(), "clipped to the field");
        assertEquals(8, zone.maxX());
        assertEquals(2, zone.minZ());
        assertEquals(18, zone.maxZ());
        assertTrue(KeeperLogic.inZone(zone, 5, 10));
        assertFalse(KeeperLogic.inZone(zone, 20, 10));
    }

    @Test
    void onlySlowBallsAtReachableHeightCanBeCaught() {
        assertTrue(KeeperLogic.canCatch(10, 18, 65.5, 64, 2.2));
        assertFalse(KeeperLogic.canCatch(20, 18, 65.5, 64, 2.2), "a hard shot has to be punched away");
        assertFalse(KeeperLogic.canCatch(10, 18, 70, 64, 2.2), "too high");
    }

    @Test
    void aSaveNeedsAnOpponentsRecentFastShotAtTheGoal() {
        assertTrue(KeeperLogic.isSave(1, 0, 20, 60, 12, 7, true));
        assertFalse(KeeperLogic.isSave(0, 0, 20, 60, 12, 7, true), "his own team played it");
        assertFalse(KeeperLogic.isSave(-1, 0, 20, 60, 12, 7, true), "nobody touched it");
        assertFalse(KeeperLogic.isSave(1, 0, 100, 60, 12, 7, true), "the shot was too long ago");
        assertFalse(KeeperLogic.isSave(1, 0, 20, 60, 3, 7, true), "a slow ball is no shot");
        assertFalse(KeeperLogic.isSave(1, 0, 20, 60, 12, 7, false), "not heading for the goal");
    }

    @Test
    void headingToIsAboutTheDirectionOfTravel() {
        assertTrue(KeeperLogic.headingTo(10, 10, -5, 0, 0, 10));
        assertFalse(KeeperLogic.headingTo(10, 10, 5, 0, 0, 10));
        assertFalse(KeeperLogic.headingTo(10, 10, 0, 0, 0, 10), "a ball at rest is going nowhere");
    }
}
