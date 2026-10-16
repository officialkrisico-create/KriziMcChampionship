package nl.kmc.laser;

import nl.kmc.laser.LaserBoard.Comp;
import nl.kmc.laser.LaserBoard.Dir;
import nl.kmc.laser.LaserBoard.Type;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LaserSimTest {

    private static Comp emitter(Dir d, String color) {
        Comp c = new Comp();
        c.type = Type.EMITTER; c.dir = d; c.color = color;
        return c;
    }

    private static Comp mirror(String id, boolean slash) {
        Comp c = new Comp();
        c.type = Type.MIRROR; c.id = id; c.slash = slash;
        return c;
    }

    private static Comp receiver(String id) {
        Comp c = new Comp();
        c.type = Type.RECEIVER; c.id = id;
        return c;
    }

    private static Comp of(Type t) {
        Comp c = new Comp();
        c.type = t;
        return c;
    }

    @Test
    void slashMirrorSendsAnEastBeamNorth() {
        LaserBoard b = new LaserBoard(7, 5);
        b.set(0, 2, emitter(Dir.E, "RED"));
        b.set(3, 2, mirror("m1", true));
        b.set(3, 0, receiver("r1"));
        var r = LaserSim.trace(b, 200);
        assertTrue(r.isActive("r1"));
        assertTrue(LaserSim.solved(b, r));
    }

    @Test
    void backslashMirrorSendsItSouthAndMissesTheReceiver() {
        LaserBoard b = new LaserBoard(7, 5);
        b.set(0, 2, emitter(Dir.E, "RED"));
        b.set(3, 2, mirror("m1", false));
        b.set(3, 0, receiver("r1"));
        var r = LaserSim.trace(b, 200);
        assertFalse(r.isActive("r1"));
        assertFalse(LaserSim.solved(b, r));
    }

    @Test
    void wallsAndAbsorbersStopTheBeam() {
        LaserBoard b = new LaserBoard(7, 3);
        b.set(0, 1, emitter(Dir.E, "RED"));
        b.set(3, 1, of(Type.WALL));
        b.set(6, 1, receiver("r1"));
        assertFalse(LaserSim.trace(b, 200).isActive("r1"));
        b.set(3, 1, of(Type.ABSORBER));
        assertFalse(LaserSim.trace(b, 200).isActive("r1"));
        b.set(3, 1, null);
        assertTrue(LaserSim.trace(b, 200).isActive("r1"));
    }

    @Test
    void receiverChecksColourAndDirection() {
        LaserBoard b = new LaserBoard(6, 3);
        b.set(0, 1, emitter(Dir.E, "RED"));
        Comp r = receiver("r1");
        r.needColor = "BLUE";
        b.set(5, 1, r);
        assertFalse(LaserSim.trace(b, 200).isActive("r1"), "wrong colour");

        Comp filter = of(Type.FILTER);
        filter.color = "BLUE";
        b.set(2, 1, filter);
        var res = LaserSim.trace(b, 200);
        assertTrue(res.isActive("r1"));
        assertEquals("BLUE", res.active.get("r1"));

        r.needDir = Dir.N;          // it now has to be hit while travelling north
        assertFalse(LaserSim.trace(b, 200).isActive("r1"), "wrong direction");
    }

    @Test
    void splitterFeedsTwoReceivers() {
        LaserBoard b = new LaserBoard(7, 7);
        b.set(0, 3, emitter(Dir.E, "RED"));
        b.set(3, 3, of(Type.SPLITTER));
        b.set(3, 0, receiver("up"));
        b.set(3, 6, receiver("down"));
        var r = LaserSim.trace(b, 200);
        assertTrue(r.isActive("up"));
        assertTrue(r.isActive("down"));
        assertTrue(LaserSim.solved(b, r));
    }

    @Test
    void aSolvedBoardNeedsEveryRequiredReceiver() {
        LaserBoard b = new LaserBoard(7, 7);
        b.set(0, 3, emitter(Dir.E, "RED"));
        b.set(3, 3, of(Type.SPLITTER));
        b.set(3, 0, receiver("up"));
        b.set(3, 6, receiver("down"));
        b.set(3, 5, of(Type.WALL));          // the lower branch is cut
        assertFalse(LaserSim.solved(b, 200));
    }

    @Test
    void loopsTerminateAndAreReported() {
        // a splitter whose branch comes back to it through a ring of mirrors
        LaserBoard b = new LaserBoard(6, 5);
        b.set(0, 2, emitter(Dir.E, "RED"));
        b.set(2, 2, of(Type.SPLITTER));
        b.set(2, 0, mirror("a", true));      // N -> E
        b.set(4, 0, mirror("b", false));     // E -> S
        b.set(4, 2, mirror("c", true));      // S -> W, back into the splitter
        var r = LaserSim.trace(b, 500);
        assertTrue(r.loop, "the repeated state is detected");
        assertFalse(r.stepLimit, "...before the step limit is needed");
        assertTrue(r.steps < 100);
    }

    @Test
    void stepLimitStopsRunawayBeams() {
        LaserBoard b = new LaserBoard(6, 5);
        b.set(0, 2, emitter(Dir.E, "RED"));
        b.set(2, 2, of(Type.SPLITTER));
        b.set(2, 0, mirror("a", true));
        b.set(4, 0, mirror("b", false));
        b.set(4, 2, mirror("c", true));
        var r = LaserSim.trace(b, 3);
        assertTrue(r.stepLimit);
        assertEquals(3, r.steps);
    }

    @Test
    void lockedMirrorsOnlyTurnWhenTheirReceiverIsActive() {
        Comp m = mirror("m", true);
        m.unlockedBy = "r1";
        var inactive = new LaserSim.Result();
        var active = new LaserSim.Result();
        active.active.put("r1", "RED");
        assertFalse(LaserSim.canRotate(m, inactive));
        assertTrue(LaserSim.canRotate(m, active));
        m.unlockedBy = null;
        m.rotatable = false;
        assertFalse(LaserSim.canRotate(m, active));
    }

    @Test
    void solverFindsTheOrientationsAndKnowsWhenThereIsNone() {
        LaserBoard b = new LaserBoard(7, 7);
        b.set(0, 5, emitter(Dir.E, "RED"));
        b.set(2, 5, mirror("m1", false));    // wrong on purpose
        b.set(2, 2, mirror("m2", true));
        b.set(5, 2, receiver("r1"));
        Map<String, Boolean> sol = LaserSim.solve(b, 200);
        assertNotNull(sol);
        assertEquals(Boolean.TRUE, sol.get("2,5"));
        assertEquals(Boolean.TRUE, sol.get("2,2"));

        b.set(3, 2, of(Type.WALL));          // the final stretch is blocked for good
        assertNull(LaserSim.solve(b, 200));
    }
}
