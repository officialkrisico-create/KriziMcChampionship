package nl.kmc.skywars.managers;

import nl.kmc.skywars.SkyWarsPlugin;
import nl.kmc.skywars.models.Island;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Stores the SkyWars arena: world + per-team island spawns + middle
 * island (chest pile location + chest search radius) + void Y level.
 *
 * <p>Setup workflow:
 * <pre>
 *   /skywars setworld &lt;world&gt;
 *   /skywars setvoidy &lt;y&gt;
 *
 *   For each team island:
 *     /skywars addisland &lt;teamId&gt;       ← stand on the island spawn
 *     (default chest search radius = 8 blocks; adjust with setradius)
 *
 *   /skywars setmiddle                   ← stand on the middle chest pile
 *   /skywars setmidradius 6               ← chest search radius for middle
 *   /skywars status
 * </pre>
 */
public class ArenaManager {

    private final SkyWarsPlugin plugin;

    private World    world;
    private int      voidYLevel;
    private Location middleSpawn;
    private int      middleRadius = 6;
    private final Map<String, Island> islands = new LinkedHashMap<>();

    public ArenaManager(SkyWarsPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        world = worldName != null ? Bukkit.getWorld(worldName) : null;
        voidYLevel = cfg.getInt("arena.void-y", 0);
        middleSpawn = readLoc(cfg, "arena.middle.spawn", world);
        middleRadius = cfg.getInt("arena.middle.radius", 6);

        islands.clear();
        ConfigurationSection isl = cfg.getConfigurationSection("arena.islands");
        if (isl != null && world != null) {
            for (String id : isl.getKeys(false)) {
                ConfigurationSection s = isl.getConfigurationSection(id);
                if (s == null) continue;
                Location spawn = readLoc(s, "spawn", world);
                if (spawn == null) continue;
                int radius = s.getInt("radius", 8);
                islands.put(id, new Island(id, spawn, radius));
            }
        }

        plugin.getLogger().info("SkyWars arena loaded: " + islands.size()
                + " islands, world=" + (world != null ? world.getName() : "none"));
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", world != null ? world.getName() : null);
        cfg.set("arena.void-y", voidYLevel);

        if (middleSpawn != null) {
            writeLoc(cfg, "arena.middle.spawn", middleSpawn);
            cfg.set("arena.middle.radius", middleRadius);
        } else {
            cfg.set("arena.middle", null);
        }

        cfg.set("arena.islands", null);
        for (Island i : islands.values()) {
            String path = "arena.islands." + i.getId();
            writeLoc(cfg, path + ".spawn", i.getSpawn());
            cfg.set(path + ".radius", i.getChestSearchRadius());
        }
        plugin.saveConfig();
    }

    private Location readLoc(org.bukkit.configuration.file.FileConfiguration cfg,
                             String path, World world) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        return readLocFromSection(s, world);
    }

    private Location readLoc(ConfigurationSection sec, String key, World world) {
        ConfigurationSection s = sec.getConfigurationSection(key);
        return readLocFromSection(s, world);
    }

    private Location readLocFromSection(ConfigurationSection s, World world) {
        if (s == null || world == null) return null;
        return new Location(world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
    }

    private void writeLoc(org.bukkit.configuration.file.FileConfiguration cfg,
                          String path, Location l) {
        cfg.set(path + ".x", l.getX());
        cfg.set(path + ".y", l.getY());
        cfg.set(path + ".z", l.getZ());
        cfg.set(path + ".yaw", l.getYaw());
        cfg.set(path + ".pitch", l.getPitch());
    }

    public World    getWorld()       { return world; }
    public void     setWorld(World w){ this.world = w; save(); }
    public int      getVoidYLevel()  { return voidYLevel; }
    public void     setVoidYLevel(int y) { this.voidYLevel = y; save(); }
    public Location getMiddleSpawn() { return middleSpawn != null ? middleSpawn.clone() : null; }
    public void     setMiddleSpawn(Location l) { this.middleSpawn = l.clone(); save(); }
    public int      getMiddleRadius(){ return middleRadius; }
    public void     setMiddleRadius(int r) { this.middleRadius = r; save(); }

    public Map<String, Island> getIslands() { return Collections.unmodifiableMap(islands); }
    public Island getIsland(String id) { return islands.get(id); }

    public void addIsland(String id, Location spawn, int radius) {
        islands.put(id, new Island(id, spawn, radius));
        save();
    }

    public void removeIsland(String id) {
        islands.remove(id);
        save();
    }

    public boolean isReady() {
        return world != null && islands.size() >= 2;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:        ").append(world != null ? "✔ " + world.getName() : "✘").append("\n");
        sb.append("Islands:      ").append(islands.size())
                .append(islands.size() < 2 ? " &c(min 2)" : "").append("\n");
        sb.append("Middle:       ").append(middleSpawn != null ? "✔" : "&7geen (optioneel)").append("\n");
        sb.append("Void Y:       ").append(voidYLevel);
        return sb.toString();
    }
}
