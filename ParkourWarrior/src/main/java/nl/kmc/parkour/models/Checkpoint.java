package nl.kmc.parkour.models;

import org.bukkit.Location;

/**
 * A single checkpoint in the parkour course.
 *
 * <p>Each checkpoint has:
 * <ul>
 *   <li>An index (1-based — checkpoint 1 is first, 0 is the start spawn)</li>
 *   <li>A 2-corner region defining the trigger area (entering it activates the checkpoint)</li>
 *   <li>A respawn location (where the player TPs to on death/fail)</li>
 *   <li>A point value (harder checkpoints = more points)</li>
 *   <li>Optional display name for stage themes ("Jungle Vines", "Nether Lava")</li>
 * </ul>
 *
 * <p>The respawn location is usually a few blocks inside the trigger
 * region so players don't immediately retrigger after dying.
 */
public class Checkpoint {

    private final int      index;
    private final Location pos1;
    private final Location pos2;
    private final Location respawn;
    private final int      points;
    private final String   displayName;

    public Checkpoint(int index, Location pos1, Location pos2, Location respawn,
                      int points, String displayName) {
        this.index       = index;
        this.pos1        = pos1;
        this.pos2        = pos2;
        this.respawn     = respawn;
        this.points      = points;
        this.displayName = displayName;
    }

    public int      getIndex()       { return index; }
    public Location getPos1()        { return pos1; }
    public Location getPos2()        { return pos2; }
    public Location getRespawn()     { return respawn; }
    public int      getPoints()      { return points; }
    public String   getDisplayName() { return displayName; }

    /** Is the given location inside this checkpoint's trigger box? */
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
