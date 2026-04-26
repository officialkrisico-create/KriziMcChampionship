package nl.kmc.elytra.models;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * A boost hoop — flying through it gives the player a forward
 * velocity kick in their current facing direction (like a rocket
 * boost). Stacks with current momentum.
 *
 * <p>Boost hoops are NOT mandatory checkpoints — players choose
 * to fly through them (or skip them) for the speed bonus.
 */
public class BoostHoop {

    private final String   id;
    private final Location pos1;
    private final Location pos2;
    private final double   strength;

    public BoostHoop(String id, Location pos1, Location pos2, double strength) {
        this.id       = id;
        this.pos1     = pos1;
        this.pos2     = pos2;
        this.strength = strength;
    }

    public String   getId()       { return id; }
    public Location getPos1()     { return pos1; }
    public Location getPos2()     { return pos2; }
    public double   getStrength() { return strength; }

    public boolean contains(Location loc) {
        if (loc == null || pos1 == null || pos2 == null) return false;
        if (!loc.getWorld().equals(pos1.getWorld())) return false;
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    /** Forward velocity kick in the player's facing direction. */
    public Vector computeBoostVelocity(Vector facing) {
        return facing.clone().normalize().multiply(strength);
    }
}
