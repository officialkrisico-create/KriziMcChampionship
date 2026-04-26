package nl.kmc.tgttos.managers;

import nl.kmc.tgttos.TGTTOSPlugin;
import nl.kmc.tgttos.models.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Manages the pool of TGTTOS maps. Each map has a unique id, world,
 * start spawns, finish region, and void Y level.
 *
 * <p>At game start, GameManager picks N random maps from the pool
 * for the game's rounds. Admin builds 5-10 maps for variety.
 *
 * <p>Setup workflow:
 * <pre>
 *   /tgttos createmap &lt;id&gt; "Display Name"
 *   /tgttos editmap &lt;id&gt; world &lt;world&gt;
 *   /tgttos editmap &lt;id&gt; addspawn        (× number of player slots)
 *   /tgttos editmap &lt;id&gt; finishpos1
 *   /tgttos editmap &lt;id&gt; finishpos2
 *   /tgttos editmap &lt;id&gt; voidy &lt;y&gt;
 *   /tgttos listmaps
 * </pre>
 */
public class MapManager {

    private final TGTTOSPlugin plugin;
    private final java.util.Map<String, Map> maps = new LinkedHashMap<>();
    private final java.util.Map<String, PartialMap> partials = new HashMap<>();

    public MapManager(TGTTOSPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        maps.clear();
        var cfg = plugin.getConfig();
        ConfigurationSection mapsSection = cfg.getConfigurationSection("maps");
        if (mapsSection == null) return;

        for (String id : mapsSection.getKeys(false)) {
            ConfigurationSection m = mapsSection.getConfigurationSection(id);
            if (m == null) continue;
            String worldName = m.getString("world");
            World world = worldName != null ? Bukkit.getWorld(worldName) : null;
            if (world == null) {
                plugin.getLogger().warning("Map " + id + " has invalid world: " + worldName);
                continue;
            }
            String displayName = m.getString("display-name", id);

            List<Location> spawns = new ArrayList<>();
            List<?> spawnList = m.getList("start-spawns");
            if (spawnList != null) {
                for (Object o : spawnList) {
                    if (!(o instanceof java.util.Map<?, ?> s)) continue;
                    Object x = s.get("x"), y = s.get("y"), z = s.get("z");
                    Object yaw = s.get("yaw"), pitch = s.get("pitch");
                    if (x == null || y == null || z == null) continue;
                    spawns.add(new Location(world,
                            ((Number) x).doubleValue(),
                            ((Number) y).doubleValue(),
                            ((Number) z).doubleValue(),
                            yaw   != null ? ((Number) yaw).floatValue()   : 0,
                            pitch != null ? ((Number) pitch).floatValue() : 0));
                }
            }

            Location finishPos1 = readLoc(m, "finish-pos1", world);
            Location finishPos2 = readLoc(m, "finish-pos2", world);
            int voidY = m.getInt("void-y", 0);

            if (finishPos1 == null || finishPos2 == null || spawns.isEmpty()) {
                plugin.getLogger().warning("Map " + id + " is incomplete, skipping.");
                continue;
            }
            maps.put(id, new Map(id, displayName, world, spawns, finishPos1, finishPos2, voidY));
        }

        plugin.getLogger().info("Loaded " + maps.size() + " TGTTOS maps.");
    }

    private Location readLoc(ConfigurationSection sec, String key, World world) {
        ConfigurationSection s = sec.getConfigurationSection(key);
        if (s == null) return null;
        return new Location(world,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("maps", null);
        for (Map m : maps.values()) {
            String path = "maps." + m.getId();
            cfg.set(path + ".display-name", m.getDisplayName());
            cfg.set(path + ".world", m.getWorld().getName());

            List<java.util.Map<String, Double>> spawnList = new ArrayList<>();
            for (Location l : m.getStartSpawns()) {
                spawnList.add(java.util.Map.of(
                        "x", l.getX(), "y", l.getY(), "z", l.getZ(),
                        "yaw", (double) l.getYaw(), "pitch", (double) l.getPitch()));
            }
            cfg.set(path + ".start-spawns", spawnList);

            cfg.set(path + ".finish-pos1.x", m.getFinishPos1().getX());
            cfg.set(path + ".finish-pos1.y", m.getFinishPos1().getY());
            cfg.set(path + ".finish-pos1.z", m.getFinishPos1().getZ());
            cfg.set(path + ".finish-pos2.x", m.getFinishPos2().getX());
            cfg.set(path + ".finish-pos2.y", m.getFinishPos2().getY());
            cfg.set(path + ".finish-pos2.z", m.getFinishPos2().getZ());
            cfg.set(path + ".void-y", m.getVoidYLevel());
        }
        plugin.saveConfig();
    }

    public java.util.Map<String, Map> getMaps() { return Collections.unmodifiableMap(maps); }
    public Map getMap(String id) { return maps.get(id); }

    /** Picks N random maps from the pool. If pool < N, picks all + repeats. */
    public List<Map> pickRandom(int n) {
        List<Map> all = new ArrayList<>(maps.values());
        Collections.shuffle(all);
        if (all.size() >= n) return all.subList(0, n);
        // Pool too small — repeat
        List<Map> result = new ArrayList<>();
        int i = 0;
        while (result.size() < n && !all.isEmpty()) {
            result.add(all.get(i % all.size()));
            i++;
        }
        return result;
    }

    public boolean isReady(int requiredMaps) {
        int ready = 0;
        for (Map m : maps.values()) if (m.isReady()) ready++;
        return ready >= 1 && ready >= Math.min(requiredMaps, 1);
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Maps:         ").append(maps.size())
                .append(maps.isEmpty() ? " &c(geen!)" : "");
        return sb.toString();
    }

    // ---- Map building (admin) ----

    public PartialMap getPartial(String id) {
        return partials.computeIfAbsent(id, k -> new PartialMap(id));
    }

    public void clearPartial(String id) { partials.remove(id); }

    public void commitPartial(String id) {
        PartialMap p = partials.get(id);
        if (p == null || !p.isComplete()) return;
        Map m = new Map(id, p.displayName, p.world, p.startSpawns,
                p.finishPos1, p.finishPos2, p.voidY != null ? p.voidY : 0);
        maps.put(id, m);
        partials.remove(id);
        save();
    }

    public void deleteMap(String id) {
        maps.remove(id);
        partials.remove(id);
        save();
    }

    public static class PartialMap {
        public final String id;
        public String   displayName;
        public World    world;
        public List<Location> startSpawns = new ArrayList<>();
        public Location finishPos1, finishPos2;
        public Integer  voidY;

        public PartialMap(String id) { this.id = id; }

        public boolean isComplete() {
            return displayName != null && world != null && !startSpawns.isEmpty()
                    && finishPos1 != null && finishPos2 != null;
        }

        public String missing() {
            if (displayName == null)    return "display-name";
            if (world == null)          return "world";
            if (startSpawns.isEmpty())  return "start-spawns";
            if (finishPos1 == null)     return "finish-pos1";
            if (finishPos2 == null)     return "finish-pos2";
            return null;
        }
    }
}
