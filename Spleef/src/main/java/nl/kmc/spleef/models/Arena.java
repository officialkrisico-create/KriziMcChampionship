package nl.kmc.spleef.models;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spleef arena definition.
 *
 * <p>Stores:
 * <ul>
 *   <li>The world this arena is in</li>
 *   <li>One or more "layer" regions — each layer is a rectangular
 *       slab of floor blocks the plugin places + tracks for regen
 *       (top layer is layer 0)</li>
 *   <li>Player spawn locations — one per player slot</li>
 *   <li>Void Y level — players below this are eliminated</li>
 * </ul>
 *
 * <p>For single-layer Spleef, layers list has one entry. For multi-
 * layer (3 floors), there are 3 entries with progressively lower Y.
 */
public class Arena {

    /** A single horizontal slab of floor blocks. */
    public static class Layer {
        private final int yLevel;
        private final int minX, maxX;
        private final int minZ, maxZ;

        public Layer(int yLevel, int minX, int maxX, int minZ, int maxZ) {
            this.yLevel = yLevel;
            this.minX = Math.min(minX, maxX);
            this.maxX = Math.max(minX, maxX);
            this.minZ = Math.min(minZ, maxZ);
            this.maxZ = Math.max(minZ, maxZ);
        }

        public int getYLevel() { return yLevel; }
        public int getMinX()   { return minX; }
        public int getMaxX()   { return maxX; }
        public int getMinZ()   { return minZ; }
        public int getMaxZ()   { return maxZ; }

        public int getBlockCount() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    private World world;
    private final List<Layer> layers = new ArrayList<>();
    private final List<Location> playerSpawns = new ArrayList<>();
    private int voidYLevel;

    public Arena() {}

    public World getWorld()              { return world; }
    public void  setWorld(World w)       { this.world = w; }
    public int   getVoidYLevel()         { return voidYLevel; }
    public void  setVoidYLevel(int y)    { this.voidYLevel = y; }

    public void addLayer(Layer l)        { layers.add(l); }
    public List<Layer> getLayers()       { return Collections.unmodifiableList(layers); }
    public void clearLayers()            { layers.clear(); }
    public Layer getTopLayer()           { return layers.isEmpty() ? null : layers.get(0); }
    public Layer getBottomLayer()        { return layers.isEmpty() ? null : layers.get(layers.size() - 1); }

    public void addPlayerSpawn(Location loc) { playerSpawns.add(loc.clone()); }
    public List<Location> getPlayerSpawns()  { return Collections.unmodifiableList(playerSpawns); }
    public void clearPlayerSpawns()          { playerSpawns.clear(); }

    public boolean isReady() {
        return world != null && !layers.isEmpty() && !playerSpawns.isEmpty();
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:          ").append(world != null ? "✔ " + world.getName() : "✘").append("\n");
        sb.append("Layers:         ").append(layers.size())
                .append(layers.isEmpty() ? " &c(geen!)" : "").append("\n");
        sb.append("Player spawns:  ").append(playerSpawns.size())
                .append(playerSpawns.isEmpty() ? " &c(geen!)" : "").append("\n");
        sb.append("Void Y:         ").append(voidYLevel);
        return sb.toString();
    }
}
