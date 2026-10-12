package nl.kmc.kmccore.presentation;

import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import nl.kmc.kmccore.presentation.camera.SplinePath;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SplinePathTest {

    private static CameraWaypoint wp(double x, double y, double z, float yaw, float pitch, int ticks) {
        return new CameraWaypoint(new Location(null, x, y, z, yaw, pitch), ticks, InterpolationType.SMOOTH, "", "", "");
    }

    @Test
    void passesThroughEveryWaypointAtItsKnotTime() {
        List<CameraWaypoint> wps = List.of(
                wp(0, 10, 0, 0, 10, 1), wp(10, 12, 0, 90, 20, 20),
                wp(10, 14, 10, 180, 30, 40), wp(0, 10, 10, -90, 10, 20));
        SplinePath p = new SplinePath(wps);
        double[] o = new double[5];
        for (int i = 0; i < wps.size(); i++) {
            p.sample(p.knotTime(i), o);
            assertEquals(wps.get(i).getX(), o[0], 1e-6);
            assertEquals(wps.get(i).getY(), o[1], 1e-6);
            assertEquals(wps.get(i).getZ(), o[2], 1e-6);
        }
        assertEquals(80.0, p.duration(), 1e-9);
    }

    @Test
    void noVelocityJumpAtInteriorWaypoint() {
        List<CameraWaypoint> wps = List.of(
                wp(0, 0, 0, 0, 0, 1), wp(20, 0, 0, 0, 0, 20), wp(20, 0, 40, 0, 0, 20));
        SplinePath p = new SplinePath(wps);
        double k = p.knotTime(1), e = 0.001;
        double[] a = new double[5], b = new double[5], c = new double[5];
        p.sample(k - e, a); p.sample(k, b); p.sample(k + e, c);
        for (int i = 0; i < 3; i++) {
            double before = (b[i] - a[i]) / e, after = (c[i] - b[i]) / e;
            assertEquals(before, after, 0.05, "velocity component " + i + " must be continuous");
        }
        // the camera never stops at the waypoint
        double speed = Math.hypot(Math.hypot((c[0] - b[0]) / e, (c[1] - b[1]) / e), (c[2] - b[2]) / e);
        assertTrue(speed > 0.5);
    }

    @Test
    void yawTakesTheShortWayRound() {
        SplinePath p = new SplinePath(List.of(wp(0, 0, 0, 170, 0, 1), wp(1, 0, 0, -170, 0, 20)));
        double[] o = new double[5];
        p.sample(10, o);
        // halfway between 170 and -170 is ±180, not 0
        assertTrue(Math.abs(Math.abs(o[3]) - 180) < 1.0, "yaw " + o[3]);
    }

    @Test
    void warpIsMonotoneAndBounded() {
        double d = 200, prev = -1;
        for (int t = 0; t <= 200; t++) {
            double u = SplinePath.warp(t, d, 24);
            assertTrue(u >= prev - 1e-9);
            assertTrue(u >= 0 && u <= d + 1e-9);
            prev = u;
        }
        assertEquals(0, SplinePath.warp(0, d, 24), 1e-9);
        assertEquals(d, SplinePath.warp(d, d, 24), 1e-6);
    }

    @Test
    void closedOrbitHasNoKinkAtTheSeam() {
        List<CameraWaypoint> wps = new ArrayList<>();
        int n = 12;
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            wps.add(wp(30 * Math.cos(a), 20, 30 * Math.sin(a), (float) Math.toDegrees(a) + 90, 30, i == 0 ? 1 : 18));
        }
        SplinePath p = new SplinePath(wps);
        double e = 0.001;
        double[] a = new double[5], b = new double[5];
        p.sample(e, a);
        p.sample(2 * e, b);
        double startVx = (b[0] - a[0]) / e;
        p.sample(p.duration() - 2 * e, a);
        p.sample(p.duration() - e, b);
        double endVx = (b[0] - a[0]) / e;
        assertEquals(startVx, endVx, 0.05);
    }
}
