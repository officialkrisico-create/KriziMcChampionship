package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-arena setup. Admin builds ONE arena in the template world;
 * the plugin clones it per-team at game start.
 *
 * <p>Stored data:
 * <ul>
 *   <li>Player spawn (x,y,z + yaw/pitch)</li>
 *   <li>Mob spawn locations (list of x,y,z)</li>
 * </ul>
 *
 * <p>These coordinates are relative to the template world. When a clone
 * is loaded, we use those same coords against the cloned world to get
 * the equivalent locations.
 *
 * <p>Setup workflow:
 * <ol>
 *   <li>Create template world manually (e.g. /mv create mm_template)</li>
 *   <li>Build the arena in that world</li>
 *   <li>/mm settemplate mm_template (registers it)</li>
 *   <li>Stand at desired player spawn → /mm setspawn</li>
 *   <li>Stand at each desired mob spawn → /mm addmobspawn</li>
 *   <li>/mm status to verify</li>
 * </ol>
 */
public class ArenaManager {

    private final MobMayhemPlugin plugin;

    /** Player spawn location IN THE TEMPLATE WORLD. */
    private double psX, psY, psZ;
    private float  psYaw, psPitch;
    private boolean playerSpawnSet;

    /** Mob spawn locations IN THE TEMPLATE WORLD (coords only). */
    private final List<double[]> mobSpawnsRaw = new ArrayList<>();

    public ArenaManager(MobMayhemPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var cfg = plugin.getConfig();
        if (cfg.contains("arena.player-spawn.x")) {
            psX = cfg.getDouble("arena.player-spawn.x");
            psY = cfg.getDouble("arena.player-spawn.y");
            psZ = cfg.getDouble("arena.player-spawn.z");
            psYaw = (float) cfg.getDouble("arena.player-spawn.yaw", 0);
            psPitch = (float) cfg.getDouble("arena.player-spawn.pitch", 0);
            playerSpawnSet = true;
        }

        mobSpawnsRaw.clear();
        var list = cfg.getList("arena.mob-spawns");
        if (list != null) {
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m)) continue;
                Object x = m.get("x"), y = m.get("y"), z = m.get("z");
                if (x == null || y == null || z == null) continue;
                mobSpawnsRaw.add(new double[]{
                        ((Number) x).doubleValue(),
                        ((Number) y).doubleValue(),
                        ((Number) z).doubleValue()
                });
            }
        }

        plugin.getLogger().info("Arena loaded: spawn=" + (playerSpawnSet ? "✔" : "✘")
                + ", " + mobSpawnsRaw.size() + " mob spawns");
    }

    public void save() {
        var cfg = plugin.getConfig();
        if (playerSpawnSet) {
            cfg.set("arena.player-spawn.x", psX);
            cfg.set("arena.player-spawn.y", psY);
            cfg.set("arena.player-spawn.z", psZ);
            cfg.set("arena.player-spawn.yaw", psYaw);
            cfg.set("arena.player-spawn.pitch", psPitch);
        }
        // Save raw coords as a list of maps
        List<java.util.Map<String, Double>> serialized = new ArrayList<>();
        for (double[] arr : mobSpawnsRaw) {
            serialized.add(java.util.Map.of("x", arr[0], "y", arr[1], "z", arr[2]));
        }
        cfg.set("arena.mob-spawns", serialized);
        plugin.saveConfig();
    }

    public void setPlayerSpawn(Location loc) {
        this.psX = loc.getX();
        this.psY = loc.getY();
        this.psZ = loc.getZ();
        this.psYaw = loc.getYaw();
        this.psPitch = loc.getPitch();
        this.playerSpawnSet = true;
        save();
    }

    public void addMobSpawn(Location loc) {
        mobSpawnsRaw.add(new double[]{loc.getX(), loc.getY(), loc.getZ()});
        save();
    }

    public void clearMobSpawns() {
        mobSpawnsRaw.clear();
        save();
    }

    public boolean isPlayerSpawnSet()   { return playerSpawnSet; }
    public int     getMobSpawnCount()   { return mobSpawnsRaw.size(); }

    /**
     * Builds a runtime {@link Arena} for the given cloned world by
     * applying the stored coords to the new world.
     */
    public Arena buildForWorld(String arenaId, World world) {
        if (!playerSpawnSet) return null;
        Location playerSpawn = new Location(world, psX, psY, psZ, psYaw, psPitch);
        Arena arena = new Arena(arenaId, playerSpawn);
        for (double[] coords : mobSpawnsRaw) {
            arena.addMobSpawn(new Location(world, coords[0], coords[1], coords[2]));
        }
        return arena;
    }

    public boolean isReady() {
        return playerSpawnSet && mobSpawnsRaw.size() >= 4;
    }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Player spawn: ").append(playerSpawnSet ? "✔" : "✘").append("\n");
        sb.append("Mob spawns:   ").append(mobSpawnsRaw.size())
                .append(mobSpawnsRaw.size() < 4 ? " &c(min 4)" : "");
        return sb.toString();
    }
}
