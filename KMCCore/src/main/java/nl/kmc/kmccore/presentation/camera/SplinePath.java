package nl.kmc.kmccore.presentation.camera;

import java.util.List;

/**
 * A smooth camera path through a route's waypoints — the "replay mod" look.
 *
 * <p>Instead of easing in and out of every waypoint (which makes the camera stop and start at each one),
 * the path is a C1-continuous cubic Hermite spline in <em>time</em>: position, yaw and pitch pass through
 * every waypoint at its scheduled tick with a continuous velocity, so the camera glides through them.
 * The per-segment durations of the route are respected (waypoint {@code k} is reached at the cumulative
 * duration of waypoints {@code 1..k}).
 *
 * <p>Playback additionally uses a speed ramp ({@link #warp}) so the camera eases out of a standstill at the
 * very start and back into one at the very end — and nowhere else.
 */
public final class SplinePath {

    private final int n;
    private final double[] t;                 // knot times (ticks), t[0] = 0
    private final double[][] v;               // v[0..4][i] = x, y, z, yaw (unwrapped), pitch
    private final double[][] m;               // tangents (units per tick)
    private final double duration;

    public SplinePath(List<CameraWaypoint> wps) {
        this.n = wps.size();
        this.t = new double[n];
        this.v = new double[5][n];
        this.m = new double[5][n];

        double acc = 0;
        double prevYaw = 0;
        for (int i = 0; i < n; i++) {
            CameraWaypoint w = wps.get(i);
            if (i > 0) acc += Math.max(1, w.getDurationTicks());
            t[i] = acc;
            v[0][i] = w.getX();
            v[1][i] = w.getY();
            v[2][i] = w.getZ();
            double yaw = w.getYaw();
            if (i > 0) {                      // unwrap so the camera always turns the short way round
                double d = yaw - prevYaw;
                d = d - 360.0 * Math.floor((d + 180.0) / 360.0);
                yaw = prevYaw + d;
            }
            v[3][i] = yaw;
            prevYaw = yaw;
            v[4][i] = Math.max(-90, Math.min(90, w.getPitch()));
        }
        this.duration = n > 0 ? t[n - 1] : 0;

        // A closed loop (last waypoint == first) gets periodic tangents so there is no kink at the seam.
        boolean closed = n >= 4
                && Math.abs(v[0][0] - v[0][n - 1]) < 0.01
                && Math.abs(v[1][0] - v[1][n - 1]) < 0.01
                && Math.abs(v[2][0] - v[2][n - 1]) < 0.01;

        for (int c = 0; c < 5; c++) {
            for (int i = 0; i < n; i++) {
                double prev, next, dt;
                if (i == 0) {
                    if (closed) {
                        prev = v[c][n - 2] - (v[c][n - 1] - v[c][0]);
                        next = v[c][1];
                        dt = (t[1] - t[0]) + (t[n - 1] - t[n - 2]);
                    } else {
                        prev = v[c][0]; next = v[c][1]; dt = t[1] - t[0];
                    }
                } else if (i == n - 1) {
                    if (closed) {
                        prev = v[c][n - 2];
                        next = v[c][1] + (v[c][n - 1] - v[c][0]);
                        dt = (t[n - 1] - t[n - 2]) + (t[1] - t[0]);
                    } else {
                        prev = v[c][n - 2]; next = v[c][n - 1]; dt = t[n - 1] - t[n - 2];
                    }
                } else {
                    prev = v[c][i - 1]; next = v[c][i + 1]; dt = t[i + 1] - t[i - 1];
                }
                m[c][i] = dt > 0 ? (next - prev) / dt : 0;
            }
        }
    }

    /** Total path duration in ticks (sum of the route's segment durations). */
    public double duration() { return duration; }

    /**
     * Maps real playback time to path time: speed ramps from 0 over the first {@code ease} ticks, stays
     * constant, and ramps back to 0 over the last {@code ease} ticks. Total time stays {@code duration}.
     */
    public static double warp(double tau, double duration, double ease) {
        double e = Math.min(ease, duration / 2.5);
        if (e <= 0 || duration <= 0) return Math.max(0, Math.min(duration, tau));
        double v = duration / (duration - e);          // cruise speed so the whole path fits in 'duration'
        if (tau <= 0) return 0;
        if (tau >= duration) return duration;
        if (tau < e) return v * tau * tau / (2 * e);
        if (tau > duration - e) {
            double r = duration - tau;
            return duration - v * r * r / (2 * e);
        }
        return v * (tau - e / 2);
    }

    /**
     * Samples the path at {@code u} ticks (0..duration).
     *
     * @param out receives x, y, z, yaw, pitch (length ≥ 5)
     */
    public void sample(double u, double[] out) {
        if (n == 0) return;
        if (n == 1 || u <= 0) { for (int c = 0; c < 5; c++) out[c] = v[c][0]; fixAngles(out); return; }
        if (u >= duration)    { for (int c = 0; c < 5; c++) out[c] = v[c][n - 1]; fixAngles(out); return; }

        int i = 0;
        while (i < n - 2 && u >= t[i + 1]) i++;
        double h = t[i + 1] - t[i];
        double s = h > 0 ? (u - t[i]) / h : 1;
        double s2 = s * s, s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1;
        double h10 = s3 - 2 * s2 + s;
        double h01 = -2 * s3 + 3 * s2;
        double h11 = s3 - s2;
        for (int c = 0; c < 5; c++) {
            out[c] = h00 * v[c][i] + h10 * h * m[c][i] + h01 * v[c][i + 1] + h11 * h * m[c][i + 1];
        }
        fixAngles(out);
    }

    private static void fixAngles(double[] o) {
        o[3] = o[3] - 360.0 * Math.floor((o[3] + 180.0) / 360.0);   // back into [-180, 180)
        o[4] = Math.max(-90, Math.min(90, o[4]));
    }

    /** Waypoint knot time in ticks. */
    public double knotTime(int i) { return t[i]; }
    public int size() { return n; }
}
