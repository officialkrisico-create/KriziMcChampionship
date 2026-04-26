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

    private final String   id;
    private final String   displayName;
    private final World    world;
    private final List<Location> startSpawns;
    private final Location finishPos1;
    private final Location finishPos2;
    private final int      voidYLevel;

    public Map(String id, String displayName, World world,
               List<Location> startSpawns,
               Location finishPos1, Location finishPos2,
               int voidYLevel) {
        this.id           = id;
        this.displayName  = displayName;
        this.world        = world;
        this.startSpawns  = new ArrayList<>(startSpawns);
        this.finishPos1   = finishPos1;
        this.finishPos2   = finishPos2;
        this.voidYLevel   = voidYLevel;
    }

    public String   getId()          { return id; }
    public String   getDisplayName() { return displayName; }
    public World    getWorld()       { return world; }
    public List<Location> getStartSpawns() { return Collections.unmodifiableList(startSpawns); }
    public Location getFinishPos1()  { return finishPos1; }
    public Location getFinishPos2()  { return finishPos2; }
    public int      getVoidYLevel()  { return voidYLevel; }

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
