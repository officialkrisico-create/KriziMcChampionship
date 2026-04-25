package nl.kmc.parkour.managers;

import nl.kmc.parkour.ParkourWarriorPlugin;
import nl.kmc.parkour.models.Checkpoint;
import nl.kmc.parkour.models.Powerup;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Manages the parkour course — checkpoints + powerups + start/world.
 *
 * <p>Checkpoints are 1-indexed in the API. Index 0 is reserved for
 * the START spawn (no points). The final checkpoint is the FINISH.
 */
public class CourseManager {

    private final ParkourWarriorPlugin plugin;

    private World    courseWorld;
    private Location startSpawn;

    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final Map<String, Powerup> powerups = new LinkedHashMap<>();

    private final Map<String, PartialCheckpoint> partials = new HashMap<>();
    private final Map<String, PartialPowerup>    partialPowerups = new HashMap<>();

    public CourseManager(ParkourWarriorPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("course.world");
        if (worldName != null && !worldName.isBlank()) courseWorld = Bukkit.getWorld(worldName);

        startSpawn = cfg.getLocation("course.start-spawn");

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
                String   name    = cp.getString("name", "Checkpoint " + key);
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

        powerups.clear();
        ConfigurationSection ps = cfg.getConfigurationSection("course.powerups");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection p = ps.getConfigurationSection(key);
                if (p == null) continue;
                String typeStr = p.getString("type", "SPEED");
                Powerup.Type t;
                try { t = Powerup.Type.valueOf(typeStr.toUpperCase()); }
                catch (IllegalArgumentException e) { continue; }
                Location p1 = p.getLocation("pos1");
                Location p2 = p.getLocation("pos2");
                if (p1 == null || p2 == null) continue;
                int dur = p.getInt("duration", 5);
                int amp = p.getInt("amplifier", 1);
                powerups.put(key, new Powerup(key, t, p1, p2, dur, amp));
            }
        }

        plugin.getLogger().info("Loaded " + checkpoints.size() + " checkpoints, "
                + powerups.size() + " powerups (world: "
                + (courseWorld != null ? courseWorld.getName() : "none") + ")");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("course.world", courseWorld != null ? courseWorld.getName() : null);
        cfg.set("course.start-spawn", startSpawn);

        cfg.set("course.checkpoints", null);
        for (Checkpoint cp : checkpoints) {
            String path = "course.checkpoints." + cp.getIndex();
            cfg.set(path + ".name",    cp.getDisplayName());
            cfg.set(path + ".pos1",    cp.getPos1());
            cfg.set(path + ".pos2",    cp.getPos2());
            cfg.set(path + ".respawn", cp.getRespawn());
            cfg.set(path + ".points",  cp.getPoints());
        }

        cfg.set("course.powerups", null);
        for (Powerup p : powerups.values()) {
            String path = "course.powerups." + p.getId();
            cfg.set(path + ".type",      p.getType().name());
            cfg.set(path + ".pos1",      p.getPos1());
            cfg.set(path + ".pos2",      p.getPos2());
            cfg.set(path + ".duration",  p.getDurationSeconds());
            cfg.set(path + ".amplifier", p.getAmplifier());
        }

        plugin.saveConfig();
    }

    // ---- Course setup --------------------------------------------

    public World    getCourseWorld()        { return courseWorld; }
    public void     setCourseWorld(World w) { this.courseWorld = w; save(); }
    public Location getStartSpawn()         { return startSpawn; }
    public void     setStartSpawn(Location loc) { this.startSpawn = loc.clone(); save(); }

    public List<Checkpoint> getCheckpoints() { return Collections.unmodifiableList(checkpoints); }

    public Checkpoint getCheckpoint(int index) {
        for (Checkpoint cp : checkpoints) if (cp.getIndex() == index) return cp;
        return null;
    }

    public Checkpoint findCheckpointAt(Location loc) {
        for (Checkpoint cp : checkpoints) if (cp.contains(loc)) return cp;
        return null;
    }

    public int getCheckpointCount() { return checkpoints.size(); }

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

    // ---- Powerups ------------------------------------------------

    public Map<String, Powerup> getPowerups() { return Collections.unmodifiableMap(powerups); }

    public Powerup findPowerupAt(Location loc) {
        for (Powerup p : powerups.values()) if (p.contains(loc)) return p;
        return null;
    }

    public void addOrUpdatePowerup(Powerup p) {
        powerups.put(p.getId(), p);
        save();
    }

    public void removePowerup(String id) {
        powerups.remove(id);
        save();
    }

    public PartialPowerup getPartialPowerup(String id) {
        return partialPowerups.computeIfAbsent(id, k -> new PartialPowerup());
    }

    public void clearPartialPowerup(String id) { partialPowerups.remove(id); }

    // ---- Partial checkpoint helpers (admin command flow) ---------

    public PartialCheckpoint getPartial(int index) {
        return partials.computeIfAbsent(String.valueOf(index), k -> new PartialCheckpoint());
    }

    public void clearPartial(int index) { partials.remove(String.valueOf(index)); }

    public static class PartialCheckpoint {
        public String   name;
        public Location pos1;
        public Location pos2;
        public Location respawn;
        public Integer  points;

        public boolean isComplete() {
            return name != null && pos1 != null && pos2 != null && respawn != null && points != null;
        }
    }

    public static class PartialPowerup {
        public Powerup.Type type;
        public Location     pos1;
        public Location     pos2;
        public Integer      duration;
        public Integer      amplifier;

        public boolean isComplete() {
            return type != null && pos1 != null && pos2 != null;
        }
    }

    // ---- Validity ------------------------------------------------

    public boolean isReady() {
        return courseWorld != null && startSpawn != null && checkpoints.size() >= 2;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:        ").append(courseWorld != null ? "✔ " + courseWorld.getName() : "✘").append("\n");
        sb.append("Start spawn:  ").append(startSpawn != null ? "✔" : "✘").append("\n");
        sb.append("Checkpoints:  ").append(checkpoints.size())
                .append(checkpoints.size() < 2 ? " &c(min 2: 1 stage + finish)" : "").append("\n");
        sb.append("Powerups:     ").append(powerups.size())
                .append(powerups.isEmpty() ? " &7(geen)" : "");
        return sb.toString();
    }
}
