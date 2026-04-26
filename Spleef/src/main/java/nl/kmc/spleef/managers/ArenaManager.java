package nl.kmc.spleef.managers;

import nl.kmc.spleef.SpleefPlugin;
import nl.kmc.spleef.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads + saves the Spleef arena definition from config.
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>/spleef setworld &lt;world&gt; — declares which world the arena is in</li>
 *   <li>/spleef setlayer pos1 — at one corner of the floor area</li>
 *   <li>/spleef setlayer pos2 — at the OPPOSITE corner of the floor area
 *       (use the same Y for both; that Y becomes the floor level)</li>
 *   <li>/spleef addspawn — at each desired player spawn (above the floor)</li>
 *   <li>/spleef setvoidy &lt;y&gt; — Y level below which players are eliminated
 *       (default: floor Y - 5)</li>
 * </ol>
 */
public class ArenaManager {

    private final SpleefPlugin plugin;
    private final Arena arena = new Arena();

    /** Temporary corners for /spleef setlayer pos1 / pos2. */
    private int[] tempPos1, tempPos2;

    public ArenaManager(SpleefPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public Arena getArena() { return arena; }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        if (worldName != null && !worldName.isBlank()) {
            arena.setWorld(Bukkit.getWorld(worldName));
        }

        arena.clearLayers();
        ConfigurationSection layersSection = cfg.getConfigurationSection("arena.layers");
        if (layersSection != null) {
            for (String key : layersSection.getKeys(false)) {
                ConfigurationSection l = layersSection.getConfigurationSection(key);
                if (l == null) continue;
                int y    = l.getInt("y");
                int minX = l.getInt("min-x"), maxX = l.getInt("max-x");
                int minZ = l.getInt("min-z"), maxZ = l.getInt("max-z");
                arena.addLayer(new Arena.Layer(y, minX, maxX, minZ, maxZ));
            }
        }

        arena.clearPlayerSpawns();
        if (arena.getWorld() != null) {
            List<?> spawnList = cfg.getList("arena.player-spawns");
            if (spawnList != null) {
                for (Object o : spawnList) {
                    if (!(o instanceof java.util.Map<?, ?> m)) continue;
                    Object x = m.get("x"), y = m.get("y"), z = m.get("z");
                    Object yaw = m.get("yaw"), pitch = m.get("pitch");
                    if (x == null || y == null || z == null) continue;
                    arena.addPlayerSpawn(new Location(arena.getWorld(),
                            ((Number) x).doubleValue(),
                            ((Number) y).doubleValue(),
                            ((Number) z).doubleValue(),
                            yaw   != null ? ((Number) yaw).floatValue()   : 0,
                            pitch != null ? ((Number) pitch).floatValue() : 0));
                }
            }
        }

        arena.setVoidYLevel(cfg.getInt("arena.void-y", 0));

        plugin.getLogger().info("Arena loaded: " + arena.getLayers().size() + " layers, "
                + arena.getPlayerSpawns().size() + " spawns, void Y=" + arena.getVoidYLevel());
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arena.getWorld() != null ? arena.getWorld().getName() : null);

        cfg.set("arena.layers", null);
        int idx = 0;
        for (Arena.Layer layer : arena.getLayers()) {
            String path = "arena.layers." + idx;
            cfg.set(path + ".y",     layer.getYLevel());
            cfg.set(path + ".min-x", layer.getMinX());
            cfg.set(path + ".max-x", layer.getMaxX());
            cfg.set(path + ".min-z", layer.getMinZ());
            cfg.set(path + ".max-z", layer.getMaxZ());
            idx++;
        }

        List<java.util.Map<String, Double>> serialized = new ArrayList<>();
        for (Location l : arena.getPlayerSpawns()) {
            serialized.add(java.util.Map.of(
                    "x",     l.getX(),
                    "y",     l.getY(),
                    "z",     l.getZ(),
                    "yaw",   (double) l.getYaw(),
                    "pitch", (double) l.getPitch()
            ));
        }
        cfg.set("arena.player-spawns", serialized);
        cfg.set("arena.void-y", arena.getVoidYLevel());
        plugin.saveConfig();
    }

    // ---- Setup helpers --------------------------------------------

    public void setWorld(World w) {
        arena.setWorld(w);
        save();
    }

    public void setLayerPos1(int x, int y, int z) {
        tempPos1 = new int[]{x, y, z};
    }

    public void setLayerPos2(int x, int y, int z) {
        tempPos2 = new int[]{x, y, z};
        if (tempPos1 != null) {
            // Use Y from pos1 (assume floor is one Y level)
            int floorY = tempPos1[1];
            arena.clearLayers();
            arena.addLayer(new Arena.Layer(floorY,
                    tempPos1[0], tempPos2[0], tempPos1[2], tempPos2[2]));
            // Default void Y = floor Y - 10 (configurable)
            arena.setVoidYLevel(floorY - 10);
            save();
            tempPos1 = null;
            tempPos2 = null;
        }
    }

    public boolean hasPos1() { return tempPos1 != null; }

    public void addPlayerSpawn(Location loc) {
        arena.addPlayerSpawn(loc);
        save();
    }

    public void clearPlayerSpawns() {
        arena.clearPlayerSpawns();
        save();
    }

    public void setVoidY(int y) {
        arena.setVoidYLevel(y);
        save();
    }
}
