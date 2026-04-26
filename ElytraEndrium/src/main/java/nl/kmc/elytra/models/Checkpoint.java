package nl.kmc.elytra.models;

import org.bukkit.Location;

/**
 * A single checkpoint / ring in the elytra course.
 *
 * <p>Region-box detection (invisible, flexible). For visible rings,
 * admin builds glowing blocks at the same location; the box is just
 * the trigger volume the plugin tracks.
 *
 * <p>Each checkpoint has:
 * <ul>
 *   <li>An index (1-based; 0 = launch spawn, no points)</li>
 *   <li>2-corner trigger box defining when a player "passes through"</li>
 *   <li>Optional respawn position (where players relaunch on crash)</li>
 *   <li>Point value (harder-to-reach checkpoints can be worth more)</li>
 *   <li>Display name (for action-bar feedback)</li>
 * </ul>
 *
 * <p>For race mode, the final checkpoint is the FINISH. For collection
 * mode, all checkpoints can be hit in any order.
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
