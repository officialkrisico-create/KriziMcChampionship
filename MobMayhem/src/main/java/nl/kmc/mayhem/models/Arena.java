package nl.kmc.mayhem.models;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One arena instance — typically one per team in multi-team mode.
 *
 * <p>Has a unique id (e.g. "arena_a"), a player spawn location, and
 * a list of mob spawn locations. Mobs spawn at random points from
 * the spawn list to avoid players camping a single chokepoint.
 *
 * <p>Optional "shrink center" + max-radius for the shrinking battlefield
 * mechanic from the spec — not yet implemented but stored here.
 */
public class Arena {

    private final String         id;
    private final Location       playerSpawn;
    private final List<Location> mobSpawns = new ArrayList<>();
    private Location              shrinkCenter;
    private int                   shrinkMaxRadius;

    public Arena(String id, Location playerSpawn) {
        this.id          = id;
        this.playerSpawn = playerSpawn;
    }

    public String   getId()          { return id; }
    public Location getPlayerSpawn() { return playerSpawn != null ? playerSpawn.clone() : null; }

    public void addMobSpawn(Location loc)    { mobSpawns.add(loc.clone()); }
    public List<Location> getMobSpawns()     { return Collections.unmodifiableList(mobSpawns); }
    public boolean hasMobSpawns()            { return !mobSpawns.isEmpty(); }

    public Location randomMobSpawn() {
        if (mobSpawns.isEmpty()) return null;
        return mobSpawns.get((int) (Math.random() * mobSpawns.size())).clone();
    }

    public Location getShrinkCenter()       { return shrinkCenter != null ? shrinkCenter.clone() : null; }
    public void     setShrinkCenter(Location c) { this.shrinkCenter = c != null ? c.clone() : null; }
    public int      getShrinkMaxRadius()    { return shrinkMaxRadius; }
    public void     setShrinkMaxRadius(int r) { this.shrinkMaxRadius = r; }
}
