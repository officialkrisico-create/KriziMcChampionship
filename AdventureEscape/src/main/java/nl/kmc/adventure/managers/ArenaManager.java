package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Arena manager: world, spawn grid, start/finish lines, laps.
 * All configuration persists to config.yml.
 */
public class ArenaManager {

    private final AdventureEscapePlugin plugin;

    private World          raceWorld;
    private List<Location> spawns = new ArrayList<>();

    // Start / finish lines as 2-corner boxes
    private Location startPos1, startPos2;
    private Location finishPos1, finishPos2;

    private int laps;

    public ArenaManager(AdventureEscapePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ----------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------

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
        plugin.saveConfig();
    }

    // ----------------------------------------------------------------
    // World
    // ----------------------------------------------------------------

    public World   getRaceWorld()        { return raceWorld; }
    public void    setRaceWorld(World w) { this.raceWorld = w; save(); }

    // ----------------------------------------------------------------
    // Spawns
    // ----------------------------------------------------------------

    public void addSpawn(Location l) {
        spawns.add(l.clone());
        save();
    }

    public void clearSpawns() { spawns.clear(); save(); }
    public List<Location> getSpawns() { return Collections.unmodifiableList(spawns); }

    // ----------------------------------------------------------------
    // Lines
    // ----------------------------------------------------------------

    public void setStartlinePos1(Location l)  { startPos1  = l.clone(); save(); }
    public void setStartlinePos2(Location l)  { startPos2  = l.clone(); save(); }
    public void setFinishlinePos1(Location l) { finishPos1 = l.clone(); save(); }
    public void setFinishlinePos2(Location l) { finishPos2 = l.clone(); save(); }

    /**
     * Returns true if the given location is within the startline box.
     * The box is defined by the two corner locations (any two points —
     * we normalise to min/max on each axis).
     */
    public boolean isInStartline(Location loc) {
        return isInBox(loc, startPos1, startPos2);
    }

    public boolean isInFinishline(Location loc) {
        return isInBox(loc, finishPos1, finishPos2);
    }

    private boolean isInBox(Location loc, Location p1, Location p2) {
        if (p1 == null || p2 == null) return false;
        if (!loc.getWorld().equals(p1.getWorld())) return false;

        double minX = Math.min(p1.getX(), p2.getX());
        double maxX = Math.max(p1.getX(), p2.getX()) + 1;  // box is inclusive of block
        double minY = Math.min(p1.getY(), p2.getY());
        double maxY = Math.max(p1.getY(), p2.getY()) + 1;
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;

        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    // ----------------------------------------------------------------
    // Laps
    // ----------------------------------------------------------------

    public int  getLaps()          { return laps; }
    public void setLaps(int laps)  { this.laps = Math.max(1, laps); save(); }

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
        sb.append("Laps:        ").append(laps);
        return sb.toString();
    }

    /** Returns spawns shuffled for varied starting grids. */
    public List<Location> getShuffledSpawns() {
        List<Location> copy = new ArrayList<>(spawns);
        Collections.shuffle(copy);
        return copy;
    }
}
