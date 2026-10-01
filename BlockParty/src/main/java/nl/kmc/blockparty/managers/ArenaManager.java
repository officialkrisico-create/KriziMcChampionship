package nl.kmc.blockparty.managers;

import nl.kmc.blockparty.BlockPartyPlugin;
import nl.kmc.blockparty.models.Colors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Round 1's fixed floor (schematic-like — built by hand, then captured), keyed by "dx,dz". Empty = round 1 falls back to the random 4-colour default. */
    private Map<String, Material> presetFloor = new HashMap<>();

    public ArenaManager(BlockPartyPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ── Loading / saving ──────────────────────────────────────────────────────

    private static final String BASE = "block-party.arena.";
    /** Where this data lived before config.yml was restructured under block-party.* — migrated automatically if found. */
    private static final String OLD_BASE = "arena.";

    public void load() {
        var cfg = plugin.getConfig();
        migrateOldFlatLayout(cfg);
        String worldName = cfg.getString(BASE + "world", "");
        world = (worldName == null || worldName.isEmpty()) ? null : Bukkit.getWorld(worldName);
        voidY = cfg.getInt(BASE + "void-y", 0);
        pos1      = readLoc(cfg.getConfigurationSection(BASE + "pos1"));
        pos2      = readLoc(cfg.getConfigurationSection(BASE + "pos2"));
        spectator = readLoc(cfg.getConfigurationSection(BASE + "spectator"));
        presetFloor = readPreset(cfg.getStringList(BASE + "preset-floor"));
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set(BASE + "world", world != null ? world.getName() : "");
        cfg.set(BASE + "void-y", voidY);
        writeLoc(BASE + "pos1", pos1);
        writeLoc(BASE + "pos2", pos2);
        writeLoc(BASE + "spectator", spectator);
        plugin.saveConfig();
    }

    /**
     * One-time migration: before config.yml was restructured under
     * {@code block-party.*}, this data lived directly at {@code arena.*}.
     * If that old section is still there and the new one is empty, copy it
     * over so an existing setup (pos1/pos2/spectator/void-y/preset-floor)
     * isn't silently lost on upgrade.
     */
    private void migrateOldFlatLayout(org.bukkit.configuration.file.FileConfiguration cfg) {
        if (cfg.contains(BASE + "world") || !cfg.isConfigurationSection(OLD_BASE.substring(0, OLD_BASE.length() - 1))) {
            return; // already on the new layout, or nothing old to migrate
        }
        plugin.getLogger().info("[BlockParty] Migrating old arena.* config layout to block-party.arena.* ...");
        cfg.set(BASE + "world", cfg.getString(OLD_BASE + "world", ""));
        cfg.set(BASE + "void-y", cfg.getInt(OLD_BASE + "void-y", 0));
        for (String key : new String[]{"pos1", "pos2", "spectator"}) {
            ConfigurationSection old = cfg.getConfigurationSection(OLD_BASE + key);
            if (old != null) cfg.set(BASE + key, old.getValues(true));
        }
        if (cfg.contains(OLD_BASE + "preset-floor")) {
            cfg.set(BASE + "preset-floor", cfg.getStringList(OLD_BASE + "preset-floor"));
        }
        cfg.set(OLD_BASE.substring(0, OLD_BASE.length() - 1), null); // drop the old section
        plugin.saveConfig();
    }

    private Map<String, Material> readPreset(List<String> lines) {
        Map<String, Material> out = new HashMap<>();
        for (String line : lines) {
            int i = line.indexOf('=');
            if (i < 0) continue;
            Material mat = Material.matchMaterial(line.substring(i + 1));
            if (mat != null) out.put(line.substring(0, i), mat);
            // else: stale/renamed material — skip
        }
        return out;
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

    /** Captures whatever concrete is currently on the floor as round 1's fixed layout. */
    public void savePresetFloor() {
        presetFloor = new HashMap<>();
        int y = floorY();
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                Material m = world.getBlockAt(x, y, z).getType();
                if (Colors.isConcrete(m)) presetFloor.put((x - minX()) + "," + (z - minZ()), m);
            }
        }
        var cfg = plugin.getConfig();
        List<String> lines = new ArrayList<>();
        presetFloor.forEach((k, v) -> lines.add(k + "=" + v.name()));
        cfg.set(BASE + "preset-floor", lines);
        plugin.saveConfig();
    }

    public void clearPresetFloor() {
        presetFloor = new HashMap<>();
        plugin.getConfig().set(BASE + "preset-floor", null);
        plugin.saveConfig();
    }

    public boolean hasPresetFloor() { return !presetFloor.isEmpty(); }
    public Map<String, Material> getPresetFloor() { return presetFloor; }

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
