package nl.kmc.tnttag.managers;

import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads + saves the TNT Tag arena: world, spawns, void Y, centre, border
 * radius, spectator spawn, and powerup spawn locations.
 */
public class ArenaManager {

    private final TNTTagPlugin plugin;
    private final Arena arena = new Arena();

    public ArenaManager(TNTTagPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public Arena getArena() { return arena; }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        arena.setWorld(worldName != null && !worldName.isBlank() ? Bukkit.getWorld(worldName) : null);

        arena.clearSpawns();
        arena.clearPowerupSpawns();
        if (arena.getWorld() != null) {
            readLocList(cfg.getList("arena.spawns")).forEach(arena::addSpawn);
            readLocList(cfg.getList("arena.powerups")).forEach(arena::addPowerupSpawn);
            arena.setCenter(readLoc(cfg.getConfigurationSection("arena.center")));
            arena.setSpectatorSpawn(readLoc(cfg.getConfigurationSection("arena.spectator")));
        }
        arena.setVoidYLevel(cfg.getInt("arena.void-y", 0));
        arena.setBorderRadius(cfg.getDouble("arena.border-radius", 0));

        plugin.getLogger().info("Arena loaded: " + arena.getSpawns().size() + " spawns, "
                + arena.getPowerupSpawns().size() + " powerup spots, border "
                + (int) arena.getBorderRadius());
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arena.getWorld() != null ? arena.getWorld().getName() : null);
        cfg.set("arena.spawns",   serialize(arena.getSpawns()));
        cfg.set("arena.powerups", serialize(arena.getPowerupSpawns()));
        cfg.set("arena.void-y", arena.getVoidYLevel());
        cfg.set("arena.border-radius", arena.getBorderRadius());
        cfg.set("arena.center",    arena.getCenter() != null ? one(arena.getCenter()) : null);
        cfg.set("arena.spectator", arena.getSpectatorSpawn() != null ? one(arena.getSpectatorSpawn()) : null);
        plugin.saveConfig();
    }

    public void setWorld(World w)             { arena.setWorld(w); save(); }
    public void addSpawn(Location loc)        { arena.addSpawn(loc); save(); }
    public void clearSpawns()                 { arena.clearSpawns(); save(); }
    public void setVoidY(int y)               { arena.setVoidYLevel(y); save(); }
    public void setCenter(Location l)         { arena.setCenter(l); save(); }
    public void setSpectatorSpawn(Location l) { arena.setSpectatorSpawn(l); save(); }
    public void setBorderRadius(double r)     { arena.setBorderRadius(r); save(); }
    public void addPowerupSpawn(Location l)   { arena.addPowerupSpawn(l); save(); }
    public void clearPowerupSpawns()          { arena.clearPowerupSpawns(); save(); }

    // ── (de)serialisation helpers ─────────────────────────────────────────────

    private List<Map<String, Double>> serialize(List<Location> locs) {
        List<Map<String, Double>> out = new ArrayList<>();
        for (Location l : locs) out.add(one(l));
        return out;
    }

    private Map<String, Double> one(Location l) {
        return Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ(),
                "yaw", (double) l.getYaw(), "pitch", (double) l.getPitch());
    }

    private List<Location> readLocList(List<?> list) {
        List<Location> out = new ArrayList<>();
        if (list == null) return out;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Location l = fromMap(m);
                if (l != null) out.add(l);
            }
        }
        return out;
    }

    private Location readLoc(org.bukkit.configuration.ConfigurationSection sec) {
        if (sec == null) return null;
        Map<String, Object> m = new java.util.HashMap<>();
        for (String k : sec.getKeys(false)) m.put(k, sec.get(k));
        return fromMap(m);
    }

    private Location fromMap(Map<?, ?> m) {
        Object x = m.get("x"), y = m.get("y"), z = m.get("z"), yaw = m.get("yaw"), pitch = m.get("pitch");
        if (x == null || y == null || z == null || arena.getWorld() == null) return null;
        return new Location(arena.getWorld(),
                ((Number) x).doubleValue(), ((Number) y).doubleValue(), ((Number) z).doubleValue(),
                yaw != null ? ((Number) yaw).floatValue() : 0,
                pitch != null ? ((Number) pitch).floatValue() : 0);
    }
}
