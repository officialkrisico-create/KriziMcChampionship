package nl.kmc.hitw.managers;

import nl.kmc.hitw.HoleInTheWallPlugin;
import nl.kmc.hitw.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/** Loads/saves the platform: world, centre block, radius, spectator spot, materials. */
public class ArenaManager {

    private final HoleInTheWallPlugin plugin;
    private final Arena arena = new Arena();

    public ArenaManager(HoleInTheWallPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public Arena getArena() { return arena; }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        arena.setWorld(worldName != null && !worldName.isBlank() ? Bukkit.getWorld(worldName) : null);
        arena.setRadius(cfg.getInt("arena.radius", 18));
        arena.setPlatformMaterial(Material.matchMaterial(cfg.getString("arena.platform-material", "SMOOTH_QUARTZ")));
        arena.setBorderMaterial(Material.matchMaterial(cfg.getString("arena.border-material", "RED_CONCRETE")));

        if (cfg.isSet("arena.center.x")) {
            arena.setCenter(cfg.getInt("arena.center.x"), cfg.getInt("arena.center.y"), cfg.getInt("arena.center.z"));
        }
        arena.setSpectator(null);
        if (arena.getWorld() != null && cfg.isSet("arena.spectator.x")) {
            arena.setSpectator(new Location(arena.getWorld(),
                    cfg.getDouble("arena.spectator.x"), cfg.getDouble("arena.spectator.y"), cfg.getDouble("arena.spectator.z"),
                    (float) cfg.getDouble("arena.spectator.yaw", 0), (float) cfg.getDouble("arena.spectator.pitch", 0)));
        }
        plugin.getLogger().info("HITW arena: " + (arena.isReady()
                ? "centre " + arena.getCx() + "," + arena.getCy() + "," + arena.getCz() + " r=" + arena.getRadius()
                : "not configured"));
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arena.getWorld() != null ? arena.getWorld().getName() : null);
        cfg.set("arena.radius", arena.getRadius());
        if (arena.isCenterSet()) {
            cfg.set("arena.center.x", arena.getCx());
            cfg.set("arena.center.y", arena.getCy());
            cfg.set("arena.center.z", arena.getCz());
        }
        Location spec = arena.getSpectatorRaw();
        if (spec != null) {
            cfg.set("arena.spectator.x", spec.getX());
            cfg.set("arena.spectator.y", spec.getY());
            cfg.set("arena.spectator.z", spec.getZ());
            cfg.set("arena.spectator.yaw", spec.getYaw());
            cfg.set("arena.spectator.pitch", spec.getPitch());
        } else {
            cfg.set("arena.spectator", null);
        }
        plugin.saveConfig();
    }

    /** Centre = the block the admin stands on. */
    public void setCenter(Location standing) {
        arena.setWorld(standing.getWorld());
        arena.setCenter(standing.getBlockX(), standing.getBlockY() - 1, standing.getBlockZ());
        save();
    }

    public void setRadius(int r) { arena.setRadius(r); save(); }

    public void setSpectator(Location l) { arena.setSpectator(l); save(); }

    public boolean isReady() { return arena.isReady(); }

    public String getReadinessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Wereld:    ").append(arena.getWorld() != null ? "✔ " + arena.getWorld().getName() : "✘").append("\n");
        sb.append("Midden:    ").append(arena.isCenterSet()
                ? "✔ " + arena.getCx() + ", " + arena.getCy() + ", " + arena.getCz() : "✘ (/hitw setcenter)").append("\n");
        sb.append("Radius:    ").append(arena.getRadius()).append("\n");
        sb.append("Spectator: ").append(arena.getSpectatorRaw() != null ? "✔" : "(standaard: boven het midden)");
        return sb.toString();
    }

    /** Convenience for callers that only need the world. */
    public World world() { return arena.getWorld(); }
}
