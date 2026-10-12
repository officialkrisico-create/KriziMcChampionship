package nl.kmc.inferno;

import nl.kmc.inferno.model.Box;
import nl.kmc.inferno.model.LavaModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LavaModelTest {

    @Test
    void waitsForTheDelayThenRisesToTheEnd() {
        LavaModel m = new LavaModel(10, 50, 10, 100, 1.0);
        assertEquals(10, m.yAt(0), 1e-9);
        assertEquals(10, m.yAt(10), 1e-9);
        assertEquals(30, m.yAt(60), 1e-9);      // halfway, linear
        assertEquals(50, m.yAt(110), 1e-9);
        assertEquals(50, m.yAt(9999), 1e-9);    // stays at the end
    }

    @Test
    void exponentMakesItSlowFirstAndFastLater() {
        LavaModel m = new LavaModel(0, 100, 0, 100, 2.0);
        assertTrue(m.yAt(50) < 50, "at half time the lava is below the halfway mark");
        assertEquals(100, m.yAt(100), 1e-9);
        assertTrue(m.speedAt(90) > m.speedAt(10));
    }

    @Test
    void secondsUntilIsTheInverseOfYAt() {
        LavaModel m = new LavaModel(5, 45, 8, 80, 1.6);
        for (double y : new double[] {10, 20, 33, 44}) {
            assertEquals(y, m.yAt(m.secondsUntil(y)), 1e-6);
        }
        assertEquals(0, m.secondsUntil(5), 1e-9);
        assertTrue(Double.isInfinite(m.secondsUntil(46)));
    }

    @Test
    void endBelowStartIsClamped() {
        LavaModel m = new LavaModel(20, 10, 0, 10, 1);
        assertEquals(20, m.yAt(5), 1e-9);
    }

    @Test
    void boxContainsFullBlocks() {
        Box b = Box.of(5, 10, 5, 0, 12, 0);          // corners in any order
        assertTrue(b.contains(0.1, 10.1, 0.1));
        assertTrue(b.contains(5.9, 12.9, 5.9));
        assertFalse(b.contains(6.0, 11, 3));
        assertFalse(b.contains(3, 13.0, 3));
        assertTrue(b.containsXZ(-1.5, 3, 2.0));
        assertFalse(b.containsXZ(-3, 3, 2.0));
        assertEquals(6, b.sizeX());
    }
}
