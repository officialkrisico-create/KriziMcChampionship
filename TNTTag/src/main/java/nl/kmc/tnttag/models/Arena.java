package nl.kmc.tnttag.models;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TNT Tag arena: spawn points, void level, a centre point (for border/chaos
 * events), a configurable shrinking-border radius, a spectator spawn, and a
 * set of powerup spawn locations.
 */
public class Arena {

    private World world;
    private final List<Location> spawns          = new ArrayList<>();
    private final List<Location> powerupSpawns   = new ArrayList<>();
    private int      voidYLevel;
    private Location center;
    private Location spectatorSpawn;
    private double   borderRadius;   // 0 = no border events

    public World getWorld()            { return world; }
    public void  setWorld(World w)     { this.world = w; }
    public int   getVoidYLevel()       { return voidYLevel; }
    public void  setVoidYLevel(int y)  { this.voidYLevel = y; }

    public void addSpawn(Location loc) { spawns.add(loc.clone()); }
    public List<Location> getSpawns()  { return Collections.unmodifiableList(spawns); }
    public void clearSpawns()          { spawns.clear(); }

    public void addPowerupSpawn(Location loc)  { powerupSpawns.add(loc.clone()); }
    public List<Location> getPowerupSpawns()   { return Collections.unmodifiableList(powerupSpawns); }
    public void clearPowerupSpawns()           { powerupSpawns.clear(); }

    public Location getCenter()                { return center != null ? center.clone() : null; }
    public void     setCenter(Location c)      { this.center = c != null ? c.clone() : null; }
    public Location getSpectatorSpawn()        { return spectatorSpawn != null ? spectatorSpawn.clone() : null; }
    public void     setSpectatorSpawn(Location s) { this.spectatorSpawn = s != null ? s.clone() : null; }
    public double   getBorderRadius()          { return borderRadius; }
    public void     setBorderRadius(double r)  { this.borderRadius = r; }

    /** Hard requirements to run a game. Center/border/spectator are recommended, not required. */
    public boolean isReady() {
        return world != null && spawns.size() >= 2;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:      ").append(world != null ? "✔ " + world.getName() : "✘").append("\n");
        sb.append("Spawns:     ").append(spawns.size()).append(spawns.size() < 2 ? " &c(min 2!)" : "").append("\n");
        sb.append("Void Y:     ").append(voidYLevel).append("\n");
        sb.append("Center:     ").append(center != null ? "✔" : "&7geen (aanbevolen)").append("\n");
        sb.append("Border:     ").append(borderRadius > 0 ? "✔ " + (int) borderRadius : "&7geen (aanbevolen)").append("\n");
        sb.append("Spectator:  ").append(spectatorSpawn != null ? "✔" : "&7geen (aanbevolen)").append("\n");
        sb.append("Powerups:   ").append(powerupSpawns.size());
        return sb.toString();
    }
}
