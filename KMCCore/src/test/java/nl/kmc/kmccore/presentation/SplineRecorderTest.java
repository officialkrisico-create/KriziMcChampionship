package nl.kmc.kmccore.presentation;

import nl.kmc.kmccore.presentation.camera.SplineRecorder;
import nl.kmc.kmccore.platform.ClipMarkers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SplineRecorderTest {

    @Test
    void straightLineKeepsOnlyTheEnds() {
        double[][] p = new double[50][];
        for (int i = 0; i < p.length; i++) p[i] = new double[] {i, 10, 2 * i};
        assertEquals(List.of(0, 49), SplineRecorder.simplifyIndices(p, 0.05));
    }

    @Test
    void cornerIsKept() {
        double[][] p = new double[21][];
        for (int i = 0; i <= 10; i++) p[i] = new double[] {i, 0, 0};
        for (int i = 1; i <= 10; i++) p[10 + i] = new double[] {10, 0, i};
        List<Integer> keep = SplineRecorder.simplifyIndices(p, 0.1);
        assertEquals(List.of(0, 10, 20), keep);
    }

    @Test
    void tinyInputsAreKeptAsIs() {
        assertEquals(List.of(), SplineRecorder.simplifyIndices(new double[0][], 1));
        assertEquals(List.of(0), SplineRecorder.simplifyIndices(new double[][] {{0, 0, 0}}, 1));
        assertEquals(List.of(0, 1), SplineRecorder.simplifyIndices(new double[][] {{0, 0, 0}, {1, 1, 1}}, 1));
    }

    @Test
    void largerToleranceKeepsFewerPoints() {
        double[][] p = new double[100][];
        for (int i = 0; i < p.length; i++) p[i] = new double[] {i, Math.sin(i / 5.0) * 3, 0};
        int fine = SplineRecorder.simplifyIndices(p, 0.05).size();
        int coarse = SplineRecorder.simplifyIndices(p, 1.0).size();
        assertTrue(coarse < fine);
        assertTrue(coarse >= 2);
    }

    @Test
    void timecodeFormatsHoursMinutesSeconds() {
        assertEquals("00:00:00", ClipMarkers.timecode(0));
        assertEquals("01:02:03", ClipMarkers.timecode((3600 + 120 + 3) * 1000L));
        assertEquals("00:00:00", ClipMarkers.timecode(-5));
    }
}
