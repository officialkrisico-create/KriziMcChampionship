package nl.kmc.skywars.util;

import org.bukkit.Location;
import org.bukkit.Particle;

/**
 * Renders the closing deathmatch FLAME particle ring around a centre point.
 *
 * <p>Extracted from {@code SkyWarsGameManagerV2} to remove the duplicated
 * rendering logic that existed in both the V1 and V2 SkyWars game managers.
 */
public final class ShrinkingRingRenderer {

    /** Starting radius when the deathmatch ring first appears. */
    private final double startRadius;

    /** Metres the ring shrinks per second (one call to {@link #tick}). */
    private final double shrinkPerTick;

    /** Minimum radius — ring stops shrinking below this. */
    private static final double MIN_RADIUS = 8.0;

    private double currentRadius = 0;

    /**
     * @param startRadius  the initial ring radius in blocks
     * @param shrinkPerTick how many blocks the ring shrinks per {@link #tick} call
     */
    public ShrinkingRingRenderer(double startRadius, double shrinkPerTick) {
        this.startRadius  = startRadius;
        this.shrinkPerTick = shrinkPerTick;
    }

    /**
     * Advances the ring by one tick: shrinks the radius and spawns FLAME
     * particles in a circle around the given centre location.
     *
     * <p>Call this from a repeating {@code BukkitTask} (e.g. every 20 ticks).
     *
     * @param centre the world centre point of the ring
     */
    public void tick(Location centre) {
        if (centre == null || centre.getWorld() == null) return;

        if (currentRadius <= 0) currentRadius = startRadius;
        currentRadius = Math.max(MIN_RADIUS, currentRadius - shrinkPerTick);

        for (int deg = 0; deg < 360; deg += 5) {
            double rad = Math.toRadians(deg);
            double x   = centre.getX() + currentRadius * Math.cos(rad);
            double z   = centre.getZ() + currentRadius * Math.sin(rad);
            for (double y = centre.getY(); y < centre.getY() + 5; y += 1.5)
                centre.getWorld().spawnParticle(Particle.FLAME, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    /** Returns the current ring radius (useful for logging or UI). */
    public double getCurrentRadius() { return currentRadius; }

    /** Resets the ring to its initial state (call when a new deathmatch starts). */
    public void reset() { currentRadius = 0; }
}
