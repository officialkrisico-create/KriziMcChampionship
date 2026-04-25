package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.Checkpoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Arena manager: world, spawn grid, start/finish lines, laps, checkpoints.
 *
 * <p>NEW: checkpoint support. Each checkpoint is a 2-corner box that
 * players must pass through in order before crossing the finish line
 * counts as a lap completion. Anti-shortcut mechanism.
 *
 * <p>Checkpoints are 1-indexed in config and stored in display order.
 */
public class ArenaManager {

    private final AdventureEscapePlugin plugin;

    private World          raceWorld;
    private List<Location> spawns = new ArrayList<>();

    private Location startPos1, startPos2;
    private Location finishPos1, finishPos2;
    private int laps;

    /** Ordered list of checkpoints. Index in list = (checkpoint number - 1). */
    private final List<Checkpoint> checkpoints = new ArrayList<>();

    public ArenaManager(AdventureEscapePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        if (worldName != null) raceWorld = Bukkit.getWorld(worldName);

        spawns.clear();
        List<?> list = cfg.getList("arena.spawns");
        if (list != null) {
            for (Object o : list) if (o instanceof Location l) spawns.add(l);
        }

        startPos1  = cfg.getLocation("arena.startline.pos1");
        startPos2  = cfg.getLocation("arena.startline.pos2");
        finishPos1 = cfg.getLocation("arena.finishline.pos1");
        finishPos2 = cfg.getLocation("arena.finishline.pos2");

        laps = cfg.getInt("arena.laps", 3);

        // Load checkpoints
        checkpoints.clear();
        ConfigurationSection cpSection = cfg.getConfigurationSection("arena.checkpoints");
        if (cpSection != null) {
            // Sort numerically by key
            List<String> keys = new ArrayList<>(cpSection.getKeys(false));
            keys.sort((a, b) -> {
                try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                catch (NumberFormatException e) { return a.compareTo(b); }
            });
            for (String key : keys) {
                ConfigurationSection cp = cpSection.getConfigurationSection(key);
                if (cp == null) continue;
                Location p1 = cp.getLocation("pos1");
                Location p2 = cp.getLocation("pos2");
                if (p1 == null || p2 == null) continue;
                int idx;
                try { idx = Integer.parseInt(key); }
                catch (NumberFormatException e) { idx = checkpoints.size() + 1; }
                checkpoints.add(new Checkpoint(idx, p1, p2));
            }
        }

        plugin.getLogger().info("Loaded " + checkpoints.size() + " checkpoints, "
                + spawns.size() + " spawns, " + laps + " laps.");
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", raceWorld != null ? raceWorld.getName() : null);
        cfg.set("arena.spawns", spawns);
        cfg.set("arena.startline.pos1",  startPos1);
        cfg.set("arena.startline.pos2",  startPos2);
        cfg.set("arena.finishline.pos1", finishPos1);
        cfg.set("arena.finishline.pos2", finishPos2);
        cfg.set("arena.laps", laps);

        // Persist checkpoints
        cfg.set("arena.checkpoints", null); // clear first
        for (Checkpoint cp : checkpoints) {
            String path = "arena.checkpoints." + cp.getIndex();
            cfg.set(path + ".pos1", cp.getPos1());
            cfg.set(path + ".pos2", cp.getPos2());
        }

        plugin.saveConfig();
    }

    // ----------------------------------------------------------------
    // World / spawns / lines (unchanged)
    // ----------------------------------------------------------------

    public World   getRaceWorld()        { return raceWorld; }
    public void    setRaceWorld(World w) { this.raceWorld = w; save(); }

    public void addSpawn(Location l) { spawns.add(l.clone()); save(); }
    public void clearSpawns() { spawns.clear(); save(); }
    public List<Location> getSpawns() { return Collections.unmodifiableList(spawns); }

    public void setStartlinePos1(Location l)  { startPos1  = l.clone(); save(); }
    public void setStartlinePos2(Location l)  { startPos2  = l.clone(); save(); }
    public void setFinishlinePos1(Location l) { finishPos1 = l.clone(); save(); }
    public void setFinishlinePos2(Location l) { finishPos2 = l.clone(); save(); }

    public boolean isInStartline(Location loc)  { return isInBox(loc, startPos1, startPos2); }
    public boolean isInFinishline(Location loc) { return isInBox(loc, finishPos1, finishPos2); }

    private boolean isInBox(Location loc, Location p1, Location p2) {
        if (p1 == null || p2 == null) return false;
        if (!loc.getWorld().equals(p1.getWorld())) return false;
        double minX = Math.min(p1.getX(), p2.getX());
        double maxX = Math.max(p1.getX(), p2.getX()) + 1;
        double minY = Math.min(p1.getY(), p2.getY());
        double maxY = Math.max(p1.getY(), p2.getY()) + 1;
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    public int  getLaps()         { return laps; }
    public void setLaps(int laps) { this.laps = Math.max(1, laps); save(); }

    // ----------------------------------------------------------------
    // CHECKPOINTS — new
    // ----------------------------------------------------------------

    /**
     * Adds a checkpoint. If pos1 is set first then pos2 second, the
     * checkpoint is finalised. If only pos1 is given, we wait for pos2
     * before adding to the list.
     *
     * <p>For simplicity, this method takes BOTH corners at once. Use
     * /ae setcheckpoint &lt;n&gt; pos1 / pos2 in the command instead.
     */
    public void addOrUpdateCheckpoint(int index, Location pos1, Location pos2) {
        // Remove existing at same index
        checkpoints.removeIf(cp -> cp.getIndex() == index);
        checkpoints.add(new Checkpoint(index, pos1, pos2));
        // Re-sort by index
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

    public List<Checkpoint> getCheckpoints() {
        return Collections.unmodifiableList(checkpoints);
    }

    public int getCheckpointCount() { return checkpoints.size(); }

    /**
     * Returns the checkpoint that contains the given location, or null.
     */
    public Checkpoint getCheckpointAt(Location loc) {
        for (Checkpoint cp : checkpoints) {
            if (cp.contains(loc)) return cp;
        }
        return null;
    }

    // ----------------------------------------------------------------
    // Validity
    // ----------------------------------------------------------------

    public boolean isReady() {
        return raceWorld != null
                && !spawns.isEmpty()
                && startPos1 != null && startPos2 != null
                && finishPos1 != null && finishPos2 != null;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("World:       ").append(raceWorld != null ? "✔ " + raceWorld.getName() : "✘").append("\n");
        sb.append("Spawns:      ").append(spawns.size()).append("\n");
        sb.append("Startline:   ").append(startPos1 != null && startPos2 != null ? "✔" : "✘").append("\n");
        sb.append("Finishline:  ").append(finishPos1 != null && finishPos2 != null ? "✔" : "✘").append("\n");
        sb.append("Laps:        ").append(laps).append("\n");
        sb.append("Checkpoints: ").append(checkpoints.size())
                .append(checkpoints.isEmpty() ? " &7(geen — sluiproutes mogelijk)" : "");
        return sb.toString();
    }

    public List<Location> getShuffledSpawns() {
        List<Location> copy = new ArrayList<>(spawns);
        Collections.shuffle(copy);
        return copy;
    }
}
