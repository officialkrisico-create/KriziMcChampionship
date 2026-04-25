package nl.kmc.quake.managers;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * QuakeCraft arena: world, spawn points, and powerup spawn locations.
 *
 * <p>Setup:
 * <ol>
 *   <li>/qc setworld &lt;world&gt;</li>
 *   <li>/qc setspawn — repeat for each spawn point (recommend 8+)</li>
 *   <li>/qc setpowerup &lt;name&gt; — places a powerup spawn marker at your feet</li>
 * </ol>
 */
public class ArenaManager {

    private final QuakeCraftPlugin plugin;

    private World          arenaWorld;
    private List<Location> spawns = new ArrayList<>();

    /** Named powerup spawn locations: "platform_a" → Location(...) */
    private final Map<String, Location> powerupLocations = new LinkedHashMap<>();

    public ArenaManager(QuakeCraftPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        if (worldName != null && !worldName.isBlank()) arenaWorld = Bukkit.getWorld(worldName);

        spawns.clear();
        List<?> list = cfg.getList("arena.spawns");
        if (list != null) {
            for (Object o : list) if (o instanceof Location l) spawns.add(l);
        }

        powerupLocations.clear();
        ConfigurationSection ps = cfg.getConfigurationSection("arena.powerup-locations");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                Location loc = ps.getLocation(key);
                if (loc != null) powerupLocations.put(key, loc);
            }
        }

        plugin.getLogger().info("Loaded " + spawns.size() + " spawns, "
                + powerupLocations.size() + " powerup locations.");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arenaWorld != null ? arenaWorld.getName() : null);
        cfg.set("arena.spawns", spawns);
        cfg.set("arena.powerup-locations", null); // clear
        for (var e : powerupLocations.entrySet()) {
            cfg.set("arena.powerup-locations." + e.getKey(), e.getValue());
        }
        plugin.saveConfig();
    }

    // ---- World / spawns -------------------------------------------

    public World   getArenaWorld()        { return arenaWorld; }
    public void    setArenaWorld(World w) { this.arenaWorld = w; save(); }

    public void addSpawn(Location l) { spawns.add(l.clone()); save(); }
    public void clearSpawns()        { spawns.clear(); save(); }
    public List<Location> getSpawns() { return Collections.unmodifiableList(spawns); }

    public Location randomSpawn() {
        if (spawns.isEmpty()) return null;
        return spawns.get(new Random().nextInt(spawns.size())).clone();
    }

    /**
     * Returns a random spawn that's far away from any of the given
     * "avoid" locations (other live players). Helps prevent
     * spawn-camping.
     */
    public Location randomSpawnAwayFrom(List<Location> avoid) {
        if (spawns.isEmpty()) return null;
        if (avoid == null || avoid.isEmpty()) return randomSpawn();

        // Pick the spawn that maximises minimum distance to any "avoid" location
        Location best = null;
        double   bestDist = -1;
        for (Location candidate : spawns) {
            double minDist = Double.MAX_VALUE;
            for (Location a : avoid) {
                if (a.getWorld() != candidate.getWorld()) continue;
                double d = candidate.distanceSquared(a);
                if (d < minDist) minDist = d;
            }
            if (minDist > bestDist) {
                bestDist = minDist;
                best = candidate;
            }
        }
        return best != null ? best.clone() : randomSpawn();
    }

    // ---- Powerup locations ----------------------------------------

    public void addPowerupLocation(String name, Location loc) {
        powerupLocations.put(name, loc.clone());
        save();
    }

    public void removePowerupLocation(String name) {
        powerupLocations.remove(name);
        save();
    }

    public void clearPowerupLocations() {
        powerupLocations.clear();
        save();
    }

    public Map<String, Location> getPowerupLocations() {
        return Collections.unmodifiableMap(powerupLocations);
    }

    // ---- Validity --------------------------------------------------

    public boolean isReady() {
        return arenaWorld != null && spawns.size() >= 2;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:             ").append(arenaWorld != null ? "✔ " + arenaWorld.getName() : "✘").append("\n");
        sb.append("Spawns:            ").append(spawns.size())
                .append(spawns.size() < 2 ? " &c(minimaal 2)" : "").append("\n");
        sb.append("Powerup locations: ").append(powerupLocations.size())
                .append(powerupLocations.isEmpty() ? " &7(geen — geen powerups)" : "");
        return sb.toString();
    }
}
