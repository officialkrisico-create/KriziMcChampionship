package nl.kmc.tnttag.models;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TNT Tag arena.
 *
 * <p>Just a list of spawn locations + a void Y level (players who
 * fall below this are eliminated, useful if your arena has gaps).
 * No region detection needed — TNT Tag is movement-driven and the
 * "it" players naturally chase others around any area you build.
 */
public class Arena {

    private World          world;
    private final List<Location> spawns = new ArrayList<>();
    private int            voidYLevel;

    public World getWorld()              { return world; }
    public void  setWorld(World w)       { this.world = w; }
    public int   getVoidYLevel()         { return voidYLevel; }
    public void  setVoidYLevel(int y)    { this.voidYLevel = y; }

    public void addSpawn(Location loc)   { spawns.add(loc.clone()); }
    public List<Location> getSpawns()    { return Collections.unmodifiableList(spawns); }
    public void clearSpawns()            { spawns.clear(); }

    public boolean isReady() {
        return world != null && !spawns.isEmpty();
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:   ").append(world != null ? "✔ " + world.getName() : "✘").append("\n");
        sb.append("Spawns:  ").append(spawns.size())
                .append(spawns.isEmpty() ? " &c(geen!)" : "").append("\n");
        sb.append("Void Y:  ").append(voidYLevel);
        return sb.toString();
    }
}
