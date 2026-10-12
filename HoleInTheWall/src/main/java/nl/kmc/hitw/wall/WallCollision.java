package nl.kmc.hitw.wall;

/**
 * Exact collision between a wall and a player — no block scanning, no "same X/Z" shortcuts.
 *
 * <p>The wall lives in its own frame: it sits at signed distance {@code s} from the arena centre along
 * its normal {@code n = (cos α, sin α)}, and is described sideways by {@code u} (along
 * {@code l = (-sin α, cos α)}) and vertically by {@code y} above the platform surface. A player is a
 * vertical capsule — a circle of {@code radius} in the XZ plane, {@code height} tall (1.8 standing,
 * 1.5 sneaking). They collide if that circle overlaps a solid {@link Rect} in the (s, u) plane AND
 * their vertical span overlaps its {@code y} range.
 *
 * <p>Two things keep it fair: the circle (not a square) means grazing the corner of a hole doesn't
 * count, and {@link #sweepHits} samples the whole path of a tick so a fast wall can't skip over a player.
 */
public final class WallCollision {

    /** Vertical slack so touching a surface exactly (landing on a low wall, brushing under a high one) isn't a hit. */
    private static final double V_EPS = 0.02;

    private WallCollision() {}

    /** Collision at a single instant. {@code floorY} is the platform surface (a player standing on it has feet at floorY). */
    public static boolean hits(WallShape shape, double cx, double cz, double floorY,
                               double s, double alpha,
                               double px, double py, double pz, double height, double radius) {
        double dx = px - cx, dz = pz - cz;
        double cos = Math.cos(alpha), sin = Math.sin(alpha);
        double sp = dx * cos + dz * sin;
        double up = -dx * sin + dz * cos;
        double rel = py - floorY;

        double ds = Math.max(Math.abs(sp - s) - shape.thickness() / 2, 0);
        if (ds >= radius) return false;

        for (Rect r : shape.solids()) {
            if (rel + height <= r.y0() + V_EPS || rel >= r.y1() - V_EPS) continue;
            double du = up < r.u0() ? r.u0() - up : (up > r.u1() ? up - r.u1() : 0);
            if (ds * ds + du * du < radius * radius) return true;
        }
        return false;
    }

    /**
     * Collision over one tick: the wall moves from ({@code s0}, {@code a0}) to ({@code s1}, {@code a1})
     * while the player moves from {@code p0} to {@code p1}. Samples enough sub-steps that neither can
     * pass through the other between samples.
     */
    public static boolean sweepHits(WallShape shape, double cx, double cz, double floorY,
                                    double s0, double a0, double s1, double a1,
                                    double p0x, double p0y, double p0z,
                                    double p1x, double p1y, double p1z,
                                    double height, double radius) {
        double playerMove = Math.sqrt(sq(p1x - p0x) + sq(p1y - p0y) + sq(p1z - p0z));
        // A rotating wall sweeps fastest at the platform edge; 40 blocks is a generous arm length.
        double wallMove = Math.abs(s1 - s0) + Math.abs(a1 - a0) * 40;
        int steps = (int) Math.max(1, Math.min(16, Math.ceil(Math.max(playerMove, wallMove) / 0.3)));

        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            if (hits(shape, cx, cz, floorY,
                    s0 + (s1 - s0) * f, a0 + (a1 - a0) * f,
                    p0x + (p1x - p0x) * f, p0y + (p1y - p0y) * f, p0z + (p1z - p0z) * f,
                    height, radius)) return true;
        }
        return false;
    }

    private static double sq(double v) { return v * v; }
}
