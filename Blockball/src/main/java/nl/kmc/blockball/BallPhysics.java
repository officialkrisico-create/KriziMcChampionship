package nl.kmc.blockball;

import java.util.ArrayList;
import java.util.List;

/**
 * Arcade physics of the ball, independent of any entity. Position and velocity here are the truth; the
 * display entity only follows. Gravity, ground friction, air drag, bounces off the field walls and blockers,
 * a speed limit and a repair step for impossible states keep the ball predictable and fair.
 */
public final class BallPhysics {

    /** An axis-aligned box. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
        public double centerX() { return (minX + maxX) / 2; }
        public double centerZ() { return (minZ + maxZ) / 2; }
    }

    /** Tuning; all values per second / in blocks. */
    public static final class Config {
        public double gravity = 24;
        public double groundFriction = 0.9;     // fraction of the horizontal speed lost per second on the ground
        public double airDrag = 0.1;
        public double bounce = 0.55;            // vertical bounce off the ground
        public double wallBounce = 0.7;         // horizontal bounce off walls
        public double maxSpeed = 26;
        public double radius = 0.45;
        public double restSpeed = 0.2;          // below this on the ground the ball stands still
    }

    public final Config cfg;
    private final Box field;
    private final List<Box> blockers = new ArrayList<>();
    private final double spawnX, spawnY, spawnZ;

    public double x, y, z, vx, vy, vz;
    private double lastGoodX, lastGoodY, lastGoodZ;
    private boolean frozen;

    public BallPhysics(Box field, double spawnX, double spawnY, double spawnZ, Config cfg) {
        this.field = field;
        this.cfg = cfg;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        reset();
    }

    public void addBlocker(Box b) { blockers.add(b); }

    public Box field() { return field; }

    public boolean frozen() { return frozen; }
    public void setFrozen(boolean f) { frozen = f; if (f) { vx = vy = vz = 0; } }

    /** Back on the centre spot, standing still. */
    public void reset() {
        x = spawnX; y = spawnY; z = spawnZ;
        vx = vy = vz = 0;
        lastGoodX = x; lastGoodY = y; lastGoodZ = z;
    }

    public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }
    public double groundY() { return field.minY() + cfg.radius; }
    public boolean onGround() { return y <= groundY() + 1e-6; }

    /**
     * A kick in the horizontal direction (dx, dz) with the given power and lift. The new velocity replaces the
     * old one (a little of it is kept), so repeated kicks cannot pile up speed; the maximum speed always holds.
     */
    public void kick(double dx, double dz, double power, double lift) {
        if (frozen) return;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return;
        dx /= len;
        dz /= len;
        vx = dx * power + vx * 0.15;
        vz = dz * power + vz * 0.15;
        vy = lift;
        clampSpeed();
    }

    /** A small push (dribbling): adds to the velocity, never beyond {@code limit} horizontally. */
    public void nudge(double dx, double dz, double strength, double limit) {
        if (frozen) return;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return;
        vx += dx / len * strength;
        vz += dz / len * strength;
        double h = Math.hypot(vx, vz);
        if (h > limit) { vx *= limit / h; vz *= limit / h; }
    }

    private void clampSpeed() {
        double s = speed();
        if (s > cfg.maxSpeed) {
            double f = cfg.maxSpeed / s;
            vx *= f; vy *= f; vz *= f;
        }
    }

    /** Advances the ball by {@code dt} seconds. */
    public void step(double dt) {
        if (frozen || dt <= 0) return;
        // integrate in small slices so a fast ball cannot tunnel through a wall or a blocker
        int slices = Math.max(1, (int) Math.ceil(speed() * dt / 0.4));
        double h = dt / slices;
        for (int i = 0; i < slices; i++) integrate(h);
        repairIfInvalid();
        if (valid()) { lastGoodX = x; lastGoodY = y; lastGoodZ = z; }
    }

    private void integrate(double dt) {
        boolean wasGround = onGround();
        if (wasGround && vy <= 0) vy = 0;                // resting on the floor: no gravity, no jitter
        else vy -= cfg.gravity * dt;
        x += vx * dt;
        y += vy * dt;
        z += vz * dt;

        double r = cfg.radius;
        // ground
        double gy = field.minY() + r;
        if (y < gy) {
            y = gy;
            if (vy < -1.2) vy = -vy * cfg.bounce;       // only a real impact bounces
            else if (vy < 0) vy = 0;
        }
        // ceiling
        double cy = field.maxY() - r;
        if (y > cy) { y = cy; if (vy > 0) vy = -vy * 0.4; }
        // side walls
        if (x < field.minX() + r) { x = field.minX() + r; vx = Math.abs(vx) * cfg.wallBounce; }
        if (x > field.maxX() - r) { x = field.maxX() - r; vx = -Math.abs(vx) * cfg.wallBounce; }
        if (z < field.minZ() + r) { z = field.minZ() + r; vz = Math.abs(vz) * cfg.wallBounce; }
        if (z > field.maxZ() - r) { z = field.maxZ() - r; vz = -Math.abs(vz) * cfg.wallBounce; }
        // blockers
        for (Box b : blockers) collide(b);

        // friction: much stronger on the ground
        double drag = (onGround() || wasGround) ? cfg.groundFriction : cfg.airDrag;
        double keep = Math.max(0, 1 - drag * dt);
        vx *= keep;
        vz *= keep;
        if (onGround() && Math.hypot(vx, vz) < cfg.restSpeed) { vx = 0; vz = 0; }
        clampSpeed();
    }

    /** Pushes the ball out of a solid box along the axis of least penetration and bounces it. */
    private void collide(Box b) {
        double r = cfg.radius;
        if (x + r <= b.minX() || x - r >= b.maxX() || z + r <= b.minZ() || z - r >= b.maxZ()
                || y + r <= b.minY() || y - r >= b.maxY()) return;
        double left = (x + r) - b.minX(), right = b.maxX() - (x - r);
        double front = (z + r) - b.minZ(), back = b.maxZ() - (z - r);
        double min = Math.min(Math.min(left, right), Math.min(front, back));
        if (min == left) { x = b.minX() - r; vx = -Math.abs(vx) * cfg.wallBounce; }
        else if (min == right) { x = b.maxX() + r; vx = Math.abs(vx) * cfg.wallBounce; }
        else if (min == front) { z = b.minZ() - r; vz = -Math.abs(vz) * cfg.wallBounce; }
        else { z = b.maxZ() + r; vz = Math.abs(vz) * cfg.wallBounce; }
    }

    // ── Validity ──────────────────────────────────────────────────────────────

    public boolean valid() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz)
                && x >= field.minX() - 0.01 && x <= field.maxX() + 0.01
                && z >= field.minZ() - 0.01 && z <= field.maxZ() + 0.01
                && y >= field.minY() - 0.01 && y <= field.maxY() + 0.01;
    }

    /** An impossible state goes back to the last good position; failing that, to the centre spot. */
    private void repairIfInvalid() {
        if (valid()) return;
        x = lastGoodX; y = lastGoodY; z = lastGoodZ;
        vx = vy = vz = 0;
        if (!valid()) reset();
    }

    // ── Goals ─────────────────────────────────────────────────────────────────

    /** Which of the two goals holds the centre of the ball, or -1. */
    public int goalAt(Box goal0, Box goal1) {
        if (goal0 != null && goal0.contains(x, y, z)) return 0;
        if (goal1 != null && goal1.contains(x, y, z)) return 1;
        return -1;
    }

    /**
     * Solid boxes that close the end zone around a goal: the part of the zone between the goal mouth and the
     * back wall that is not the goal itself. Works for goals at any of the four field ends.
     */
    public static List<Box> endZoneBlockers(Box field, Box goal) {
        List<Box> out = new ArrayList<>();
        double eps = 0.6;
        double fy0 = field.minY(), fy1 = field.maxY();
        if (Math.abs(goal.minX() - field.minX()) <= eps) {          // goal on the west end
            out.add(new Box(field.minX(), fy0, field.minZ(), goal.maxX(), fy1, goal.minZ()));
            out.add(new Box(field.minX(), fy0, goal.maxZ(), goal.maxX(), fy1, field.maxZ()));
        } else if (Math.abs(goal.maxX() - field.maxX()) <= eps) {   // east end
            out.add(new Box(goal.minX(), fy0, field.minZ(), field.maxX(), fy1, goal.minZ()));
            out.add(new Box(goal.minX(), fy0, goal.maxZ(), field.maxX(), fy1, field.maxZ()));
        } else if (Math.abs(goal.minZ() - field.minZ()) <= eps) {   // north end
            out.add(new Box(field.minX(), fy0, field.minZ(), goal.minX(), fy1, goal.maxZ()));
            out.add(new Box(goal.maxX(), fy0, field.minZ(), field.maxX(), fy1, goal.maxZ()));
        } else if (Math.abs(goal.maxZ() - field.maxZ()) <= eps) {   // south end
            out.add(new Box(field.minX(), fy0, goal.minZ(), goal.minX(), fy1, field.maxZ()));
            out.add(new Box(goal.maxX(), fy0, goal.minZ(), field.maxX(), fy1, field.maxZ()));
        }
        out.removeIf(b -> b.maxX() - b.minX() < 0.05 || b.maxZ() - b.minZ() < 0.05);
        return out;
    }
}
