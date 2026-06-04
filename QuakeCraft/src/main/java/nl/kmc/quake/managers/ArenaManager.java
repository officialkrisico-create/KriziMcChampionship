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

    /** Jump pads — each with its own launch strength. */
    private final List<nl.kmc.quake.models.JumpPad> jumpPads = new ArrayList<>();

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

        jumpPads.clear();
        ConfigurationSection pads = cfg.getConfigurationSection("arena.jump-pads");
        if (pads != null) {
            for (String key : pads.getKeys(false)) {
                ConfigurationSection pad = pads.getConfigurationSection(key);
                if (pad == null) continue;
                Location loc = pad.getLocation("location");
                if (loc == null) continue;
                double height   = pad.getDouble("height", 4.0);
                double vertical = pad.getDouble("vertical", nl.kmc.quake.models.JumpPad.heightToVelocity(height));
                double forward  = pad.getDouble("forward", 0.4);
                jumpPads.add(new nl.kmc.quake.models.JumpPad(loc, vertical, forward, height));
            }
        }

        plugin.getLogger().info("Loaded " + spawns.size() + " spawns, "
                + powerupLocations.size() + " powerup locations, "
                + jumpPads.size() + " jump pads.");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arenaWorld != null ? arenaWorld.getName() : null);
        cfg.set("arena.spawns", spawns);
        cfg.set("arena.powerup-locations", null); // clear
        for (var e : powerupLocations.entrySet()) {
            cfg.set("arena.powerup-locations." + e.getKey(), e.getValue());
        }
        cfg.set("arena.jump-pads", null); // clear
        for (int i = 0; i < jumpPads.size(); i++) {
            var pad = jumpPads.get(i);
            String path = "arena.jump-pads." + i;
            cfg.set(path + ".location", pad.getLocation());
            cfg.set(path + ".height",   pad.getTargetHeight());
            cfg.set(path + ".vertical", pad.getVerticalVelocity());
            cfg.set(path + ".forward",  pad.getForward());
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

    // ---- Jump pads ------------------------------------------------

    /**
     * Registers the block the player is standing on as a jump pad with a
     * specific target height (blocks) and forward boost. If a pad already
     * exists on that block it is replaced with the new strength.
     */
    public void addJumpPad(Location playerLoc, double height, double forward) {
        Location pad = playerLoc.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getLocation();
        jumpPads.removeIf(jp -> jp.matchesBlock(pad)); // replace if present
        double vertical = nl.kmc.quake.models.JumpPad.heightToVelocity(height);
        jumpPads.add(new nl.kmc.quake.models.JumpPad(pad, vertical, forward, height));
        save();
    }

    /** Removes the jump pad nearest to (and within 2 blocks of) the given location. */
    public boolean removeNearestJumpPad(Location near) {
        Location floor = near.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getLocation();
        nl.kmc.quake.models.JumpPad best = null;
        double bestDist = 4.0; // within 2 blocks (squared)
        for (var pad : jumpPads) {
            Location l = pad.getLocation();
            if (l.getWorld() != floor.getWorld()) continue;
            double d = l.distanceSquared(floor);
            if (d <= bestDist) { bestDist = d; best = pad; }
        }
        if (best != null) { jumpPads.remove(best); save(); return true; }
        return false;
    }

    public void clearJumpPads() { jumpPads.clear(); save(); }

    public List<nl.kmc.quake.models.JumpPad> getJumpPads() { return Collections.unmodifiableList(jumpPads); }

    /** Returns the jump pad at the given block location, or null. */
    public nl.kmc.quake.models.JumpPad getJumpPadAt(Location blockLoc) {
        if (blockLoc == null) return null;
        for (var pad : jumpPads) {
            if (pad.matchesBlock(blockLoc)) return pad;
        }
        return null;
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
