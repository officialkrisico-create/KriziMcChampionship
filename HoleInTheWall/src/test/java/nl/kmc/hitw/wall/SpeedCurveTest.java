package nl.kmc.hitw.wall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeedCurveTest {

    private final SpeedCurve curve = new SpeedCurve(List.of(
            new SpeedCurve.Key(60, 16), new SpeedCurve.Key(0, 5),
            new SpeedCurve.Key(40, 12), new SpeedCurve.Key(20, 8)));   // deliberately unsorted

    @Test
    void startsSlowAndInterpolatesBetweenKeyframes() {
        assertEquals(5,    curve.speedAt(0),  1e-9);
        assertEquals(6.5,  curve.speedAt(10), 1e-9);
        assertEquals(8,    curve.speedAt(20), 1e-9);
        assertEquals(10,   curve.speedAt(30), 1e-9);
        assertEquals(14,   curve.speedAt(50), 1e-9);
    }

    @Test
    void clampsBeforeTheFirstAndAfterTheLastKeyframe() {
        assertEquals(5,  curve.speedAt(-10), 1e-9);
        assertEquals(16, curve.speedAt(60),  1e-9);
        assertEquals(16, curve.speedAt(600), 1e-9);
    }

    @Test
    void anEmptyConfigStillGivesAUsableCurve() {
        assertEquals(6, new SpeedCurve(List.of()).speedAt(123), 1e-9);
    }
}
