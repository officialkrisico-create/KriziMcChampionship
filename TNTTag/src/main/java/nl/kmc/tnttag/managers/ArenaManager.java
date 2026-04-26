package nl.kmc.tnttag.managers;

import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads + saves the TNT Tag arena from config.
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>/tnttag setworld &lt;world&gt;</li>
 *   <li>/tnttag addspawn — at each desired spawn point (one per max player slot)</li>
 *   <li>/tnttag setvoidy &lt;y&gt; — Y level below which players fall to elimination</li>
 * </ol>
 */
public class ArenaManager {

    private final TNTTagPlugin plugin;
    private final Arena arena = new Arena();

    public ArenaManager(TNTTagPlugin plugin) {
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

        arena.clearSpawns();
        if (arena.getWorld() != null) {
            List<?> spawnList = cfg.getList("arena.spawns");
            if (spawnList != null) {
                for (Object o : spawnList) {
                    if (!(o instanceof java.util.Map<?, ?> m)) continue;
                    Object x = m.get("x"), y = m.get("y"), z = m.get("z");
                    Object yaw = m.get("yaw"), pitch = m.get("pitch");
                    if (x == null || y == null || z == null) continue;
                    arena.addSpawn(new Location(arena.getWorld(),
                            ((Number) x).doubleValue(),
                            ((Number) y).doubleValue(),
                            ((Number) z).doubleValue(),
                            yaw   != null ? ((Number) yaw).floatValue()   : 0,
                            pitch != null ? ((Number) pitch).floatValue() : 0));
                }
            }
        }

        arena.setVoidYLevel(cfg.getInt("arena.void-y", 0));

        plugin.getLogger().info("Arena loaded: " + arena.getSpawns().size() + " spawns, void Y="
                + arena.getVoidYLevel());
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arena.getWorld() != null ? arena.getWorld().getName() : null);

        List<java.util.Map<String, Double>> serialized = new ArrayList<>();
        for (Location l : arena.getSpawns()) {
            serialized.add(java.util.Map.of(
                    "x",     l.getX(),
                    "y",     l.getY(),
                    "z",     l.getZ(),
                    "yaw",   (double) l.getYaw(),
                    "pitch", (double) l.getPitch()
            ));
        }
        cfg.set("arena.spawns", serialized);
        cfg.set("arena.void-y", arena.getVoidYLevel());
        plugin.saveConfig();
    }

    public void setWorld(World w) {
        arena.setWorld(w);
        save();
    }

    public void addSpawn(Location loc) {
        arena.addSpawn(loc);
        save();
    }

    public void clearSpawns() {
        arena.clearSpawns();
        save();
    }

    public void setVoidY(int y) {
        arena.setVoidYLevel(y);
        save();
    }
}
