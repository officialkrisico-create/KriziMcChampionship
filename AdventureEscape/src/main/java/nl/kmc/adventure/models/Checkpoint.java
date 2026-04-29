package nl.kmc.adventure.models;

import org.bukkit.Location;

/**
 * A checkpoint on the race track. Defined as a 2-corner box (like
 * start/finish lines). Players must pass through ALL checkpoints in
 * order before they can complete a lap — this is the anti-shortcut
 * mechanism.
 */
public class Checkpoint {

    /** 1-indexed checkpoint number (used for sort order + display). */
    private final int      index;
    private final Location pos1;
    private final Location pos2;

    public Checkpoint(int index, Location pos1, Location pos2) {
        this.index = index;
        this.pos1  = pos1;
        this.pos2  = pos2;
    }

    public int      getIndex() { return index; }
    public Location getPos1()  { return pos1; }
    public Location getPos2()  { return pos2; }

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