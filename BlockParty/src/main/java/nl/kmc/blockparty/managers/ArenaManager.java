package nl.kmc.blockparty.managers;

import nl.kmc.blockparty.BlockPartyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores and validates the Block Party arena: the floor rectangle (two
 * corners), the spectator spawn, and the void Y. The floor itself is never
 * stored — it's generated fresh every round by {@link FloorGenerator}.
 */
public final class ArenaManager {

    private final BlockPartyPlugin plugin;

    private World    world;
    private Location pos1, pos2;
    private Location spectator;
    private int      voidY;

    public ArenaManager(BlockPartyPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ── Loading / saving ──────────────────────────────────────────────────────

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world", "");
        world = (worldName == null || worldName.isEmpty()) ? null : Bukkit.getWorld(worldName);
        voidY = cfg.getInt("arena.void-y", 0);
        pos1      = readLoc(cfg.getConfigurationSection("arena.pos1"));
        pos2      = readLoc(cfg.getConfigurationSection("arena.pos2"));
        spectator = readLoc(cfg.getConfigurationSection("arena.spectator"));
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", world != null ? world.getName() : "");
        cfg.set("arena.void-y", voidY);
        writeLoc("arena.pos1", pos1);
        writeLoc("arena.pos2", pos2);
        writeLoc("arena.spectator", spectator);
        plugin.saveConfig();
    }

    private Location readLoc(ConfigurationSection s) {
        if (s == null || world == null || !s.contains("x")) return null;
        return new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw", 0), (float) s.getDouble("pitch", 0));
    }

    private void writeLoc(String path, Location l) {
        if (l == null) { plugin.getConfig().set(path, new java.util.HashMap<>()); return; }
        var cfg = plugin.getConfig();
        cfg.set(path + ".x", l.getX());
        cfg.set(path + ".y", l.getY());
        cfg.set(path + ".z", l.getZ());
        cfg.set(path + ".yaw", (double) l.getYaw());
        cfg.set(path + ".pitch", (double) l.getPitch());
    }

    // ── Setters (used by setup commands / dashboard) ──────────────────────────

    public void setCorner1(Location l) { this.world = l.getWorld(); this.pos1 = l.clone(); save(); }
    public void setCorner2(Location l) { this.world = l.getWorld(); this.pos2 = l.clone(); save(); }
    public void setSpectator(Location l) { this.spectator = l.clone(); save(); }
    public void setVoidY(int y) { this.voidY = y; save(); }

    // ── Geometry ──────────────────────────────────────────────────────────────

    public World    getWorld()     { return world; }
    public Location getSpectator() { return spectator; }
    public int      getVoidY()     { return voidY; }
    public Location getPos1()      { return pos1; }
    public Location getPos2()      { return pos2; }

    public int minX() { return Math.min(pos1.getBlockX(), pos2.getBlockX()); }
    public int maxX() { return Math.max(pos1.getBlockX(), pos2.getBlockX()); }
    public int minZ() { return Math.min(pos1.getBlockZ(), pos2.getBlockZ()); }
    public int maxZ() { return Math.max(pos1.getBlockZ(), pos2.getBlockZ()); }
    /** The floor layer is the lowest Y of the two corners. */
    public int floorY() { return Math.min(pos1.getBlockY(), pos2.getBlockY()); }

    public int area() {
        if (pos1 == null || pos2 == null) return 0;
        return (maxX() - minX() + 1) * (maxZ() - minZ() + 1);
    }

    public boolean isReady() { return issues().isEmpty(); }

    public List<String> issues() {
        List<String> out = new ArrayList<>();
        if (world == null)       out.add("Arena-wereld niet ingesteld (/blockparty pos1)");
        if (pos1 == null)        out.add("Vloer-hoek 1 niet ingesteld (/blockparty pos1)");
        if (pos2 == null)        out.add("Vloer-hoek 2 niet ingesteld (/blockparty pos2)");
        if (spectator == null)   out.add("Spectator-spawn niet ingesteld (/blockparty spectator)");
        if (pos1 != null && pos2 != null && area() < 64)
            out.add("Vloer te klein (" + area() + " blokken, min. 64)");
        return out;
    }
}
