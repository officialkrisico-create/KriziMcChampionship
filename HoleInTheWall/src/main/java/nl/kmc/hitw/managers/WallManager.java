package nl.kmc.hitw.managers;

import nl.kmc.hitw.config.HitwConfig;
import nl.kmc.hitw.models.Arena;
import nl.kmc.hitw.wall.Rect;
import nl.kmc.hitw.wall.Wall;
import nl.kmc.hitw.wall.WallBuildContext;
import nl.kmc.hitw.wall.WallShape;
import nl.kmc.hitw.wall.WallSpec;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Owns the walls: spawns them, moves them every tick, draws them, and removes them again.
 *
 * <p>Walls are {@link BlockDisplay} entities (one per solid rectangle), so a wall of any size is a
 * handful of entities rather than thousands of blocks. They are sent to clients with a short
 * teleport-interpolation, aimed slightly ahead, so movement is smooth while the server only
 * updates them every {@code update-ticks}. Collision is NOT physical — it is computed from the
 * wall's geometry (see {@link nl.kmc.hitw.wall.WallCollision}), so it is exact and cheap.
 */
public final class WallManager {

    /** Tag on every wall display, so strays (after a crash/reload) can be found and removed. */
    public static final String TAG = "kmc_hitw_wall";

    private final JavaPlugin plugin;
    private final HitwConfig cfg;
    private final Arena arena;
    private final List<Wall> walls = new ArrayList<>();
    private int nextId = 1;
    private long ticks;

    public WallManager(JavaPlugin plugin, HitwConfig cfg, Arena arena) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.arena = arena;
    }

    public List<Wall> walls() { return walls; }

    /** Distance from the centre at which walls appear (beyond the platform edge, out over the void). */
    public double spawnDistance() { return arena.getRadius() + cfg.spawnMargin; }

    /** Walls are removed once they pass this (negative) distance. */
    public double exitDistance() { return -(arena.getRadius() + cfg.exitMargin); }

    /** Build context for a wall created now. */
    public WallBuildContext context(double anchorU, double reach, double gapScale, Random rnd) {
        double half = arena.getRadius() + 4;
        return new WallBuildContext(half, anchorU, reach, arena.getRadius() * 0.88, gapScale, rnd);
    }

    public Wall spawn(WallSpec spec, WallBuildContext ctx) {
        WallShape shape = cfg.wallTypes.build(spec.typeId(), ctx);
        Wall wall = new Wall(nextId++, shape, spec, spawnDistance());
        createDisplays(wall);
        walls.add(wall);
        return wall;
    }

    /**
     * Moves every wall one tick. {@code baseSpeed} is the round's current wall speed in blocks/second.
     * Returns the walls that left the arena this tick (already removed).
     */
    public List<Wall> advance(double dtSeconds, double baseSpeed) {
        ticks++;
        List<Wall> gone = new ArrayList<>();
        boolean redraw = ticks % cfg.updateTicks == 0;
        double lookAhead = cfg.updateTicks * dtSeconds;

        for (Wall w : walls) {
            w.prevS = w.s;
            w.prevAlpha = w.alpha;
            double v = baseSpeed * w.speedFactor;
            w.s -= v * dtSeconds;
            w.alpha += w.omega * dtSeconds;

            if (!w.clearedPlatform && w.s + w.shape.thickness() / 2 < -arena.getRadius()) w.clearedPlatform = true;

            if (w.s < exitDistance()) { gone.add(w); continue; }
            if (redraw) updateDisplays(w, w.s - v * lookAhead, w.alpha + w.omega * lookAhead);
        }
        for (Wall w : gone) { removeDisplays(w); walls.remove(w); }
        return gone;
    }

    /** Removes every wall (end of round / game / stop). */
    public void clear() {
        for (Wall w : walls) removeDisplays(w);
        walls.clear();
    }

    /** Removes leftover wall displays in the arena world that no live wall owns (e.g. after a crash). */
    public void removeStrays() {
        World world = arena.getWorld();
        if (world == null) return;
        for (BlockDisplay d : world.getEntitiesByClass(BlockDisplay.class)) {
            if (d.getScoreboardTags().contains(TAG)) d.remove();
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void createDisplays(Wall w) {
        World world = arena.getWorld();
        Material mat = Material.matchMaterial(w.shape.material());
        if (mat == null || !mat.isBlock()) mat = Material.ORANGE_CONCRETE;
        var blockData = mat.createBlockData();

        for (Rect r : w.shape.solids()) {
            Location at = rectLocation(r, w.s, w.alpha);
            float width = (float) r.width(), height = (float) r.height(), depth = (float) w.shape.thickness();
            BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(blockData);
                // Scale the unit block to the rectangle; the translation centres it on the entity so that
                // turning the entity (yaw) turns the wall around its own middle.
                e.setTransformation(new Transformation(
                        new Vector3f(-width / 2f, 0f, -depth / 2f), new AxisAngle4f(),
                        new Vector3f(width, height, depth), new AxisAngle4f()));
                e.setTeleportDuration(cfg.updateTicks);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(4f);
                e.setShadowRadius(0f);
                e.setShadowStrength(0f);
                e.setPersistent(false);
                e.addScoreboardTag(TAG);
            });
            w.displaysMutable().add(d);
        }
    }

    private void updateDisplays(Wall w, double s, double alpha) {
        List<Rect> solids = w.shape.solids();
        List<BlockDisplay> displays = w.displaysMutable();
        for (int i = 0; i < displays.size() && i < solids.size(); i++) {
            BlockDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            d.teleport(rectLocation(solids.get(i), s, alpha));
        }
    }

    private void removeDisplays(Wall w) {
        for (BlockDisplay d : w.displaysMutable()) if (d.isValid()) d.remove();
        w.displaysMutable().clear();
    }

    /**
     * World position of a rectangle's bottom-centre for a wall at distance {@code s} / direction
     * {@code alpha}. The display yaw is {@code alpha + 90°} so the model's X axis runs along the wall.
     */
    private Location rectLocation(Rect r, double s, double alpha) {
        double nx = Math.cos(alpha), nz = Math.sin(alpha);
        double lx = -Math.sin(alpha), lz = Math.cos(alpha);
        double um = (r.u0() + r.u1()) / 2;
        double x = arena.centerX() + s * nx + um * lx;
        double z = arena.centerZ() + s * nz + um * lz;
        return new Location(arena.getWorld(), x, arena.floorY() + r.y0(), z,
                (float) Math.toDegrees(alpha) + 90f, 0f);
    }
}
