package nl.kmc.quake.models;

import org.bukkit.Location;

/**
 * A single jump pad: the block location plus its own launch strength.
 *
 * <p>Each pad stores its vertical launch velocity (computed from a target
 * height in blocks) and a forward boost, so different pads can throw players
 * different distances — one pad 3 blocks up, another 7, etc.
 */
public final class JumpPad {

    private final Location location;
    private final double   verticalVelocity;
    private final double   forward;
    private final double   targetHeight; // kept for display in /qc listjumppads

    public JumpPad(Location location, double verticalVelocity, double forward, double targetHeight) {
        this.location         = location;
        this.verticalVelocity = verticalVelocity;
        this.forward          = forward;
        this.targetHeight     = targetHeight;
    }

    public Location getLocation()         { return location; }
    public double   getVerticalVelocity() { return verticalVelocity; }
    public double   getForward()          { return forward; }
    public double   getTargetHeight()     { return targetHeight; }

    /**
     * Converts a desired jump height (in blocks) into the initial vertical
     * velocity needed. Peak height scales with velocity squared, so the
     * inverse is a square root. The factor is tuned slightly high to offset
     * air drag so the player actually reaches roughly the requested height.
     */
    public static double heightToVelocity(double heightBlocks) {
        return Math.sqrt(Math.max(0.1, heightBlocks)) * 0.42;
    }

    public boolean matchesBlock(Location blockLoc) {
        return blockLoc != null
                && location.getWorld() != null
                && location.getWorld().equals(blockLoc.getWorld())
                && location.getBlockX() == blockLoc.getBlockX()
                && location.getBlockY() == blockLoc.getBlockY()
                && location.getBlockZ() == blockLoc.getBlockZ();
    }
}
