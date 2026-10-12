package nl.kmc.hitw.wall;

/**
 * The plan for one upcoming wall: what type, which side of the arena it comes from, how it swings.
 *
 * @param angleRad          direction of the wall's normal: it appears on that side of the arena
 *                          ({@code 0} = +X/east, {@code π/2} = +Z/south) and travels toward the opposite side
 * @param rotationRadPerSec swing around the arena centre while it travels (0 = straight)
 * @param speedMult         pattern-level speed multiplier (the wall type's own multiplier is applied on top)
 */
public record WallSpec(String typeId, double angleRad, double rotationRadPerSec, double speedMult) {

    /** True if {@code other} approaches from a different side (so the previous wall must clear first). */
    public boolean differentSide(WallSpec other) {
        double d = Math.abs(angleRad - other.angleRad) % (Math.PI * 2);
        return Math.min(d, Math.PI * 2 - d) > 1e-6;
    }
}
