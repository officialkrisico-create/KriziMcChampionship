package nl.kmc.parkour.models;

import org.bukkit.Location;

/**
 * A map-placed powerup zone. When a player walks into it, they get
 * the configured potion effect for a short duration.
 *
 * <p>Two types:
 * <ul>
 *   <li>SPEED — temporary speed boost</li>
 *   <li>JUMP — temporary jump boost</li>
 * </ul>
 *
 * <p>Each powerup has a small cooldown per player so walking back
 * over it doesn't refresh constantly.
 */
public class Powerup {

    public enum Type { SPEED, JUMP }

    private final String   id;          // unique name for admin reference
    private final Type     type;
    private final Location pos1;
    private final Location pos2;
    private final int      durationSeconds;
    private final int      amplifier;   // 0 = level I, 1 = level II, etc.

    public Powerup(String id, Type type, Location pos1, Location pos2,
                   int durationSeconds, int amplifier) {
        this.id              = id;
        this.type            = type;
        this.pos1            = pos1;
        this.pos2            = pos2;
        this.durationSeconds = Math.max(1, durationSeconds);
        this.amplifier       = Math.max(0, amplifier);
    }

    public String   getId()              { return id; }
    public Type     getType()            { return type; }
    public Location getPos1()            { return pos1; }
    public Location getPos2()            { return pos2; }
    public int      getDurationSeconds() { return durationSeconds; }
    public int      getAmplifier()       { return amplifier; }

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
}
