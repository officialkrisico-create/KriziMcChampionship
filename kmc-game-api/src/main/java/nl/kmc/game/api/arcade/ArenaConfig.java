package nl.kmc.game.api.arcade;

import nl.kmc.game.api.arcade.ArcadeLogic.Region;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Typed access to an arcade game's arena data in its config.yml: the arena world, named locations, lists of
 * locations and named regions, plus the admins' pos1/pos2 selections. Everything is stored under
 * {@code arena.*} so the tuning keys of the game stay separate from what {@code /<game>} setup writes.
 */
public final class ArenaConfig {

    private final JavaPlugin plugin;
    private final Map<UUID, Location[]> picks = new HashMap<>();

    public ArenaConfig(JavaPlugin plugin) { this.plugin = plugin; }

    private org.bukkit.configuration.file.FileConfiguration cfg() { return plugin.getConfig(); }

    // ── World ─────────────────────────────────────────────────────────────────

    public World world() {
        String n = cfg().getString("arena.world", "");
        return n == null || n.isBlank() ? null : Bukkit.getWorld(n);
    }

    public String worldName() { return cfg().getString("arena.world", ""); }

    public void setWorld(World w) {
        cfg().set("arena.world", w != null ? w.getName() : "");
        plugin.saveConfig();
    }

    // ── Single locations ──────────────────────────────────────────────────────

    public Location loc(String path) {
        ConfigurationSection s = cfg().getConfigurationSection("arena." + path);
        return s == null ? null : fromSection(s);
    }

    public void setLoc(String path, Location l) {
        // a section, not a raw map: a map only becomes readable through getConfigurationSection after a reload,
        // and the readiness check of /kmctest runs before the game reloads its config
        if (l == null) cfg().set("arena." + path, null);
        else cfg().createSection("arena." + path, toMap(l));
        plugin.saveConfig();
    }

    // ── Location lists ────────────────────────────────────────────────────────

    public List<Location> locs(String path) {
        List<Location> out = new ArrayList<>();
        for (Object o : cfg().getList("arena." + path, List.of())) {
            Location l = fromObject(o);
            if (l != null) out.add(l);
        }
        return out;
    }

    public int addLoc(String path, Location l) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Location x : locs(path)) raw.add(toMap(x));
        raw.add(toMap(l));
        cfg().set("arena." + path, raw);
        if (world() == null && l.getWorld() != null) cfg().set("arena.world", l.getWorld().getName());
        plugin.saveConfig();
        return raw.size();
    }

    public void clearLocs(String path) {
        cfg().set("arena." + path, null);
        plugin.saveConfig();
    }

    // ── Named regions ─────────────────────────────────────────────────────────

    public Map<String, Region> regions(String path) {
        Map<String, Region> out = new LinkedHashMap<>();
        ConfigurationSection s = cfg().getConfigurationSection("arena." + path);
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(id);
            if (r == null || !r.isSet("x1")) continue;
            out.put(id, Region.ofBlocks(worldName(), r.getInt("x1"), r.getInt("y1"), r.getInt("z1"),
                    r.getInt("x2"), r.getInt("y2"), r.getInt("z2")));
        }
        return out;
    }

    public Region region(String path) {
        ConfigurationSection r = cfg().getConfigurationSection("arena." + path);
        if (r == null || !r.isSet("x1")) return null;
        return Region.ofBlocks(worldName(), r.getInt("x1"), r.getInt("y1"), r.getInt("z1"),
                r.getInt("x2"), r.getInt("y2"), r.getInt("z2"));
    }

    /** Stores the region by its block corners (the selection's positions). */
    public void setRegion(String path, Location a, Location b) {
        String p = "arena." + path;
        cfg().set(p + ".x1", a.getBlockX()); cfg().set(p + ".y1", a.getBlockY()); cfg().set(p + ".z1", a.getBlockZ());
        cfg().set(p + ".x2", b.getBlockX()); cfg().set(p + ".y2", b.getBlockY()); cfg().set(p + ".z2", b.getBlockZ());
        if (world() == null && a.getWorld() != null) cfg().set("arena.world", a.getWorld().getName());
        plugin.saveConfig();
    }

    public void remove(String path) {
        cfg().set("arena." + path, null);
        plugin.saveConfig();
    }

    // ── Selections ────────────────────────────────────────────────────────────

    public void pick(Player p, int which, Location at) {
        picks.computeIfAbsent(p.getUniqueId(), k -> new Location[2])[which] = at.getBlock().getLocation();
    }

    /** The admin's two selected corners, or null when one of them is missing. */
    public Location[] selection(Player p) {
        Location[] s = picks.get(p.getUniqueId());
        return s != null && s[0] != null && s[1] != null && s[0].getWorld() == s[1].getWorld() ? s : null;
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private static Map<String, Object> toMap(Location l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
        m.put("yaw", (double) l.getYaw()); m.put("pitch", (double) l.getPitch());
        return m;
    }

    private Location fromObject(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        World w = world();
        if (w == null) return null;
        try {
            return new Location(w, ((Number) m.get("x")).doubleValue(), ((Number) m.get("y")).doubleValue(),
                    ((Number) m.get("z")).doubleValue(),
                    m.get("yaw") instanceof Number yw ? yw.floatValue() : 0f,
                    m.get("pitch") instanceof Number pt ? pt.floatValue() : 0f);
        } catch (Exception e) { return null; }
    }

    private Location fromSection(ConfigurationSection s) {
        return fromObject(s.getValues(false));
    }
}
