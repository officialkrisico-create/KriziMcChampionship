package nl.kmc.elytra.managers;

import nl.kmc.elytra.ElytraEndriumPlugin;
import nl.kmc.elytra.models.BoostHoop;
import nl.kmc.elytra.models.Checkpoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Course definition: world + launch spawn + ordered checkpoints + boost hoops.
 *
 * <p>Setup workflow:
 * <pre>
 *   /ee setworld &lt;world&gt;
 *   /ee setlaunch         ← stand at the elevated launch point
 *
 *   For each checkpoint:
 *     /ee cp &lt;n&gt; name "Tower 1"
 *     /ee cp &lt;n&gt; pos1   ← stand at first corner of trigger box
 *     /ee cp &lt;n&gt; pos2   ← second corner
 *     /ee cp &lt;n&gt; respawn ← where to TP on crash
 *     /ee cp &lt;n&gt; points 10
 *
 *   For each boost hoop:
 *     /ee boost &lt;id&gt; &lt;forward|upward&gt; pos1
 *     /ee boost &lt;id&gt; &lt;forward|upward&gt; pos2
 *     /ee boost &lt;id&gt; &lt;forward|upward&gt; strength 1.5
 * </pre>
 */
public class CourseManager {

    private final ElytraEndriumPlugin plugin;

    private World    courseWorld;
    private Location launchSpawn;

    private final List<Checkpoint>     checkpoints = new ArrayList<>();
    private final Map<String, BoostHoop> boostHoops = new LinkedHashMap<>();

    private final Map<Integer, PartialCheckpoint> partials       = new HashMap<>();
    private final Map<String,  PartialBoost>      partialBoosts  = new HashMap<>();

    public CourseManager(ElytraEndriumPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("course.world");
        if (worldName != null && !worldName.isBlank()) courseWorld = Bukkit.getWorld(worldName);
        launchSpawn = cfg.getLocation("course.launch-spawn");

        checkpoints.clear();
        ConfigurationSection cps = cfg.getConfigurationSection("course.checkpoints");
        if (cps != null) {
            List<String> keys = new ArrayList<>(cps.getKeys(false));
            keys.sort((a, b) -> {
                try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                catch (NumberFormatException e) { return a.compareTo(b); }
            });
            for (String key : keys) {
                ConfigurationSection cp = cps.getConfigurationSection(key);
                if (cp == null) continue;
                Location p1      = cp.getLocation("pos1");
                Location p2      = cp.getLocation("pos2");
                Location respawn = cp.getLocation("respawn");
                int      points  = cp.getInt("points", 10);
                String   name    = cp.getString("name", "Hoop " + key);
                if (p1 == null || p2 == null || respawn == null) {
                    plugin.getLogger().warning("Skipping malformed checkpoint " + key);
                    continue;
                }
                int idx;
                try { idx = Integer.parseInt(key); }
                catch (NumberFormatException e) { idx = checkpoints.size() + 1; }
                checkpoints.add(new Checkpoint(idx, p1, p2, respawn, points, name));
            }
        }

        boostHoops.clear();
        ConfigurationSection bhs = cfg.getConfigurationSection("course.boost-hoops");
        if (bhs != null) {
            for (String key : bhs.getKeys(false)) {
                ConfigurationSection b = bhs.getConfigurationSection(key);
                if (b == null) continue;
                Location p1 = b.getLocation("pos1");
                Location p2 = b.getLocation("pos2");
                if (p1 == null || p2 == null) continue;
                double strength = b.getDouble("strength", 1.5);
                boostHoops.put(key, new BoostHoop(key, p1, p2, strength));
            }
        }

        plugin.getLogger().info("Course loaded: " + checkpoints.size() + " checkpoints, "
                + boostHoops.size() + " boost hoops (world: "
                + (courseWorld != null ? courseWorld.getName() : "none") + ")");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("course.world", courseWorld != null ? courseWorld.getName() : null);
        cfg.set("course.launch-spawn", launchSpawn);

        cfg.set("course.checkpoints", null);
        for (Checkpoint cp : checkpoints) {
            String path = "course.checkpoints." + cp.getIndex();
            cfg.set(path + ".name",    cp.getDisplayName());
            cfg.set(path + ".pos1",    cp.getPos1());
            cfg.set(path + ".pos2",    cp.getPos2());
            cfg.set(path + ".respawn", cp.getRespawn());
            cfg.set(path + ".points",  cp.getPoints());
        }

        cfg.set("course.boost-hoops", null);
        for (BoostHoop b : boostHoops.values()) {
            String path = "course.boost-hoops." + b.getId();
            cfg.set(path + ".pos1",     b.getPos1());
            cfg.set(path + ".pos2",     b.getPos2());
            cfg.set(path + ".strength", b.getStrength());
        }
        plugin.saveConfig();
    }

    // ---- Course setup --------------------------------------------

    public World    getCourseWorld()           { return courseWorld; }
    public void     setCourseWorld(World w)    { this.courseWorld = w; save(); }
    public Location getLaunchSpawn()           { return launchSpawn; }
    public void     setLaunchSpawn(Location l) { this.launchSpawn = l.clone(); save(); }

    public List<Checkpoint> getCheckpoints() { return Collections.unmodifiableList(checkpoints); }

    public Checkpoint getCheckpoint(int index) {
        for (Checkpoint cp : checkpoints) if (cp.getIndex() == index) return cp;
        return null;
    }

    public Checkpoint findCheckpointAt(Location loc) {
        for (Checkpoint cp : checkpoints) if (cp.contains(loc)) return cp;
        return null;
    }

    public Checkpoint getFinish() {
        return checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1);
    }

    public void addOrUpdateCheckpoint(int index, String name, Location pos1, Location pos2,
                                      Location respawn, int points) {
        checkpoints.removeIf(cp -> cp.getIndex() == index);
        checkpoints.add(new Checkpoint(index, pos1, pos2, respawn, points, name));
        checkpoints.sort(Comparator.comparingInt(Checkpoint::getIndex));
        save();
    }

    public void removeCheckpoint(int index) {
        checkpoints.removeIf(cp -> cp.getIndex() == index);
        save();
    }

    public void clearCheckpoints() {
        checkpoints.clear();
        save();
    }

    // ---- Boost hoops --------------------------------------------

    public Map<String, BoostHoop> getBoostHoops() { return Collections.unmodifiableMap(boostHoops); }

    public BoostHoop findBoostAt(Location loc) {
        for (BoostHoop b : boostHoops.values()) if (b.contains(loc)) return b;
        return null;
    }

    public void addOrUpdateBoost(BoostHoop b) {
        boostHoops.put(b.getId(), b);
        save();
    }

    public void removeBoost(String id) {
        boostHoops.remove(id);
        save();
    }

    // ---- Partial helpers (admin command builder pattern) --------

    public PartialCheckpoint getPartial(int index) {
        return partials.computeIfAbsent(index, k -> new PartialCheckpoint());
    }
    public void clearPartial(int index) { partials.remove(index); }

    public PartialBoost getPartialBoost(String id) {
        return partialBoosts.computeIfAbsent(id, k -> new PartialBoost());
    }
    public void clearPartialBoost(String id) { partialBoosts.remove(id); }

    public static class PartialCheckpoint {
        public String   name;
        public Location pos1, pos2, respawn;
        public Integer  points;
        public boolean isComplete() {
            return name != null && pos1 != null && pos2 != null && respawn != null && points != null;
        }
    }

    public static class PartialBoost {
        public Location pos1, pos2;
        public Double  strength;
        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }
    }

    // ---- Validity ------------------------------------------------

    public boolean isReady() {
        return courseWorld != null && launchSpawn != null && checkpoints.size() >= 1;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:        ").append(courseWorld != null ? "✔ " + courseWorld.getName() : "✘").append("\n");
        sb.append("Launch spawn: ").append(launchSpawn != null ? "✔" : "✘").append("\n");
        sb.append("Checkpoints:  ").append(checkpoints.size())
                .append(checkpoints.isEmpty() ? " &c(min 1)" : "").append("\n");
        sb.append("Boost hoops:  ").append(boostHoops.size())
                .append(boostHoops.isEmpty() ? " &7(geen)" : "");
        return sb.toString();
    }
}
