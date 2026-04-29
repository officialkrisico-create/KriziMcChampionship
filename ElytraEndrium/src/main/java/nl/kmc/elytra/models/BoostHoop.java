package nl.kmc.elytra.models;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * A boost hoop — flying through it gives the player a velocity kick.
 *
 * <p>Two types:
 * <ul>
 *   <li>FORWARD — kick in the direction the player is facing
 *       (good for straightaways and momentum). This is the default.</li>
 *   <li>UPWARD — kick upward + slightly forward (good for climbs)</li>
 * </ul>
 *
 * <p>Boost hoops are NOT mandatory checkpoints — players can choose
 * to fly through them or skip them. They award no points; the only
 * reward is the speed/altitude boost itself.
 */
public class BoostHoop {

    public enum Type { FORWARD, UPWARD }

    private final String   id;
    private final Type     type;
    private final Location pos1;
    private final Location pos2;
    private final double   strength;

    /**
     * Full constructor — explicit Type.
     */
    public BoostHoop(String id, Type type, Location pos1, Location pos2, double strength) {
        this.id       = id;
        this.type     = type != null ? type : Type.FORWARD;
        this.pos1     = pos1;
        this.pos2     = pos2;
        this.strength = strength;
    }

    /**
     * Backwards-compatible constructor — defaults Type to FORWARD.
     * Used by older CourseManager / ElytraCommand code that doesn't
     * specify a type.
     */
    public BoostHoop(String id, Location pos1, Location pos2, double strength) {
        this(id, Type.FORWARD, pos1, pos2, strength);
    }

    public String   getId()       { return id; }
    public Type     getType()     { return type; }
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

    /**
     * Computes the velocity to apply, given the player's current
     * facing direction. Behavior depends on this hoop's Type.
     */
    public Vector computeBoostVelocity(Vector facing) {
        return switch (type) {
            case FORWARD -> facing.clone().normalize().multiply(strength);
            case UPWARD  -> {
                Vector v = facing.clone().normalize().multiply(strength * 0.4);
                v.setY(strength * 0.9);
                yield v;
            }
        };
    }
}