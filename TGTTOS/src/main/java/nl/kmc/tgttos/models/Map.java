package nl.kmc.tgttos.models;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One TGTTOS map: a starting area where all players line up, plus a
 * finish region they have to reach.
 *
 * <p>For a TGTTOS rotation, you build N different maps (different
 * obstacle layouts, different themes) and the plugin picks 3 of
 * them per game.
 */
public class Map {

    /** Sentinel for voidYLevel meaning "no void floor configured for this map". */
    public static final int NO_VOID = Integer.MIN_VALUE;

    /** How close (blocks) a player needs to be to a checkpoint to trigger it. */
    private static final double CHECKPOINT_RADIUS = 2.5;

    private final String   id;
    private final String   displayName;
    private final World    world;
    private final List<Location> startSpawns;
    private final List<Location> checkpoints;
    private final Location finishPos1;
    private final Location finishPos2;
    private final int      voidYLevel;

    public Map(String id, String displayName, World world,
               List<Location> startSpawns,
               Location finishPos1, Location finishPos2,
               int voidYLevel) {
        this(id, displayName, world, startSpawns, List.of(), finishPos1, finishPos2, voidYLevel);
    }

    public Map(String id, String displayName, World world,
               List<Location> startSpawns, List<Location> checkpoints,
               Location finishPos1, Location finishPos2,
               int voidYLevel) {
        this.id           = id;
        this.displayName  = displayName;
        this.world        = world;
        this.startSpawns  = new ArrayList<>(startSpawns);
        this.checkpoints  = new ArrayList<>(checkpoints);
        this.finishPos1   = finishPos1;
        this.finishPos2   = finishPos2;
        this.voidYLevel   = voidYLevel;
    }

    public String   getId()          { return id; }
    public String   getDisplayName() { return displayName; }
    public World    getWorld()       { return world; }
    public List<Location> getStartSpawns() { return Collections.unmodifiableList(startSpawns); }
    public List<Location> getCheckpoints() { return Collections.unmodifiableList(checkpoints); }
    public Location getFinishPos1()  { return finishPos1; }
    public Location getFinishPos2()  { return finishPos2; }
    public int      getVoidYLevel()  { return voidYLevel; }
    public boolean  hasVoidFloor()   { return voidYLevel != NO_VOID; }

    /** True once the player has fallen below this map's configured void floor. */
    public boolean isBelowVoid(Location loc) {
        return hasVoidFloor() && loc != null && loc.getY() < voidYLevel;
    }

    /**
     * Index (0-based) of the checkpoint the player is standing in/near, or -1.
     * Only checkpoints AFTER the player's current one matter to callers —
     * this just reports proximity, the caller decides whether it's progress.
     */
    public int checkpointIndexAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return -1;
        for (int i = 0; i < checkpoints.size(); i++) {
            Location cp = checkpoints.get(i);
            if (cp.getWorld() != null && cp.getWorld().equals(loc.getWorld())
                    && cp.distanceSquared(loc) <= CHECKPOINT_RADIUS * CHECKPOINT_RADIUS) {
                return i;
            }
        }
        return -1;
    }

    /** Best known respawn point for a runner currently at checkpoint index (-1 = none reached). */
    public Location respawnPointFor(int lastCheckpointIndex, List<Location> fallbackStartSpawns, int fallbackIndex) {
        if (lastCheckpointIndex >= 0 && lastCheckpointIndex < checkpoints.size()) {
            return checkpoints.get(lastCheckpointIndex);
        }
        if (fallbackStartSpawns.isEmpty()) return null;
        return fallbackStartSpawns.get(Math.floorMod(fallbackIndex, fallbackStartSpawns.size()));
    }

    /** Did the player just enter the finish region? */
    public boolean isInFinishRegion(Location loc) {
        if (loc == null || finishPos1 == null || finishPos2 == null) return false;
        if (!loc.getWorld().equals(finishPos1.getWorld())) return false;
        double minX = Math.min(finishPos1.getX(), finishPos2.getX());
        double maxX = Math.max(finishPos1.getX(), finishPos2.getX()) + 1;
        double minY = Math.min(finishPos1.getY(), finishPos2.getY());
        double maxY = Math.max(finishPos1.getY(), finishPos2.getY()) + 1;
        double minZ = Math.min(finishPos1.getZ(), finishPos2.getZ());
        double maxZ = Math.max(finishPos1.getZ(), finishPos2.getZ()) + 1;
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    public boolean isReady() {
        return world != null && !startSpawns.isEmpty()
            && finishPos1 != null && finishPos2 != null;
    }
}
