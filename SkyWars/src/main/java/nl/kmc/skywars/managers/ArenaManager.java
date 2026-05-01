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
    /**
     * Mid-ring islands — small intermediate islands between team islands
     * and the middle. Hold "tier 2" loot (better than team islands, worse
     * than middle). Identified by an id; no team association.
     */
    private final Map<String, Island> midRingIslands = new LinkedHashMap<>();

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
                Island island = new Island(id, spawn, radius);
                // Optional team binding
                String teamId = s.getString("team-id");
                if (teamId != null && !teamId.isEmpty()) {
                    island.setTeamId(teamId);
                }
                // Optional per-player spawns
                if (s.isList("player-spawns")) {
                    for (Object raw : s.getList("player-spawns")) {
                        if (!(raw instanceof java.util.Map<?, ?> m)) continue;
                        try {
                            double x = ((Number) m.get("x")).doubleValue();
                            double y = ((Number) m.get("y")).doubleValue();
                            double z = ((Number) m.get("z")).doubleValue();
                            float yaw = m.containsKey("yaw") ? ((Number) m.get("yaw")).floatValue() : 0f;
                            float pitch = m.containsKey("pitch") ? ((Number) m.get("pitch")).floatValue() : 0f;
                            island.addPlayerSpawn(new Location(world, x, y, z, yaw, pitch));
                        } catch (Exception ignored) {}
                    }
                }
                islands.put(id, island);
            }
        }

        midRingIslands.clear();
        ConfigurationSection mr = cfg.getConfigurationSection("arena.mid-ring-islands");
        if (mr != null && world != null) {
            for (String id : mr.getKeys(false)) {
                ConfigurationSection s = mr.getConfigurationSection(id);
                if (s == null) continue;
                Location spawn = readLoc(s, "spawn", world);
                if (spawn == null) continue;
                int radius = s.getInt("radius", 6);
                midRingIslands.put(id, new Island(id, spawn, radius));
            }
        }

        plugin.getLogger().info("SkyWars arena loaded: " + islands.size()
                + " islands, " + midRingIslands.size() + " mid-ring islands, world="
                + (world != null ? world.getName() : "none"));
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
            if (i.getTeamId() != null) {
                cfg.set(path + ".team-id", i.getTeamId());
            }
            // Per-player spawns — store as a list of maps
            if (i.playerSpawnCount() > 0) {
                List<java.util.Map<String, Object>> ps = new ArrayList<>();
                for (Location l : i.getPlayerSpawns()) {
                    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("x", l.getX());
                    entry.put("y", l.getY());
                    entry.put("z", l.getZ());
                    entry.put("yaw", (double) l.getYaw());
                    entry.put("pitch", (double) l.getPitch());
                    ps.add(entry);
                }
                cfg.set(path + ".player-spawns", ps);
            }
        }

        cfg.set("arena.mid-ring-islands", null);
        for (Island i : midRingIslands.values()) {
            String path = "arena.mid-ring-islands." + i.getId();
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

    /**
     * Bind a KMCCore team to an island. Pass null/empty to unbind.
     * @return true if the island exists and was modified
     */
    public boolean setIslandTeam(String islandId, String kmcTeamId) {
        Island i = islands.get(islandId);
        if (i == null) return false;
        i.setTeamId(kmcTeamId);
        save();
        return true;
    }

    /** Add a per-player spawn to an island. Returns false if island unknown. */
    public boolean addPlayerSpawn(String islandId, Location spawn) {
        Island i = islands.get(islandId);
        if (i == null) return false;
        i.addPlayerSpawn(spawn);
        save();
        return true;
    }

    /** Clear all per-player spawns on an island. */
    public boolean clearPlayerSpawns(String islandId) {
        Island i = islands.get(islandId);
        if (i == null) return false;
        i.clearPlayerSpawns();
        save();
        return true;
    }

    // ---- Mid-ring islands ----

    public Map<String, Island> getMidRingIslands() { return Collections.unmodifiableMap(midRingIslands); }
    public Island getMidRingIsland(String id) { return midRingIslands.get(id); }

    public void addMidRingIsland(String id, Location spawn, int radius) {
        midRingIslands.put(id, new Island(id, spawn, radius));
        save();
    }

    public void removeMidRingIsland(String id) {
        midRingIslands.remove(id);
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
