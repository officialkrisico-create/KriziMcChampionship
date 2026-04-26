package nl.kmc.sg.models;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Survival Games arena.
 *
 * <p>Stores:
 * <ul>
 *   <li>The world (pre-built by admin)</li>
 *   <li>Cornucopia center (where the bloodbath chests are)</li>
 *   <li>List of spawn pedestals around the cornucopia</li>
 *   <li>Border center + initial radius + min radius (deathmatch shrinks toward this)</li>
 *   <li>Void Y (below this = elimination via fall)</li>
 * </ul>
 *
 * <p>Chests are NOT explicitly stored — the plugin scans the entire
 * arena world for chests within the border radius and stocks them
 * with random loot at game start.
 */
public class Arena {

    private World    world;
    private Location cornucopiaCenter;
    private final List<Location> spawnPedestals = new ArrayList<>();
    private double   borderRadius;
    private double   borderMinRadius;
    private int      voidYLevel;

    public World    getWorld()                { return world; }
    public void     setWorld(World w)         { this.world = w; }
    public Location getCornucopiaCenter()     { return cornucopiaCenter != null ? cornucopiaCenter.clone() : null; }
    public void     setCornucopiaCenter(Location l) { this.cornucopiaCenter = l != null ? l.clone() : null; }
    public List<Location> getSpawnPedestals() { return Collections.unmodifiableList(spawnPedestals); }
    public void     addSpawnPedestal(Location l) { spawnPedestals.add(l.clone()); }
    public void     clearSpawnPedestals()     { spawnPedestals.clear(); }
    public double   getBorderRadius()         { return borderRadius; }
    public void     setBorderRadius(double r) { this.borderRadius = r; }
    public double   getBorderMinRadius()      { return borderMinRadius; }
    public void     setBorderMinRadius(double r) { this.borderMinRadius = r; }
    public int      getVoidYLevel()           { return voidYLevel; }
    public void     setVoidYLevel(int y)      { this.voidYLevel = y; }

    public boolean isReady() {
        return world != null && cornucopiaCenter != null && !spawnPedestals.isEmpty()
            && borderRadius > 0;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:        ").append(world != null ? "✔ " + world.getName() : "✘").append("\n");
        sb.append("Cornucopia:   ").append(cornucopiaCenter != null ? "✔" : "✘").append("\n");
        sb.append("Pedestals:    ").append(spawnPedestals.size())
                .append(spawnPedestals.isEmpty() ? " &c(geen!)" : "").append("\n");
        sb.append("Border:       ").append(borderRadius).append(" → ").append(borderMinRadius)
                .append(borderRadius <= 0 ? " &c(niet ingesteld)" : "").append("\n");
        sb.append("Void Y:       ").append(voidYLevel);
        return sb.toString();
    }
}
