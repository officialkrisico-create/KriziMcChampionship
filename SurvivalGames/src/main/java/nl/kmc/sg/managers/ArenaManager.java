package nl.kmc.sg.managers;

import nl.kmc.sg.SurvivalGamesPlugin;
import nl.kmc.sg.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads + saves the Survival Games arena from config.
 *
 * <p>Setup workflow:
 * <pre>
 *   /sg setworld &lt;world&gt;
 *   /sg setcornucopia                ← stand at center of cornucopia
 *   /sg addpedestal                  ← stand on each pedestal in turn
 *   /sg setborder &lt;radius&gt; [&lt;minRadius&gt;]   ← initial border + final
 *   /sg setvoidy &lt;y&gt;
 * </pre>
 */
public class ArenaManager {

    private final SurvivalGamesPlugin plugin;
    private final Arena arena = new Arena();

    public ArenaManager(SurvivalGamesPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public Arena getArena() { return arena; }

    public void load() {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("arena.world");
        if (worldName != null && !worldName.isBlank()) {
            arena.setWorld(Bukkit.getWorld(worldName));
        }

        if (arena.getWorld() != null) {
            ConfigurationSection cor = cfg.getConfigurationSection("arena.cornucopia");
            if (cor != null) {
                arena.setCornucopiaCenter(new Location(arena.getWorld(),
                        cor.getDouble("x"), cor.getDouble("y"), cor.getDouble("z")));
            }

            arena.clearSpawnPedestals();
            List<?> pedList = cfg.getList("arena.pedestals");
            if (pedList != null) {
                for (Object o : pedList) {
                    if (!(o instanceof java.util.Map<?, ?> m)) continue;
                    Object x = m.get("x"), y = m.get("y"), z = m.get("z");
                    if (x == null || y == null || z == null) continue;
                    arena.addSpawnPedestal(new Location(arena.getWorld(),
                            ((Number) x).doubleValue(),
                            ((Number) y).doubleValue(),
                            ((Number) z).doubleValue(),
                            m.get("yaw") != null ? ((Number) m.get("yaw")).floatValue() : 0,
                            m.get("pitch") != null ? ((Number) m.get("pitch")).floatValue() : 0));
                }
            }
        }

        arena.setBorderRadius(cfg.getDouble("arena.border-radius", 0));
        arena.setBorderMinRadius(cfg.getDouble("arena.border-min-radius", 0));
        arena.setVoidYLevel(cfg.getInt("arena.void-y", 0));

        plugin.getLogger().info("SG arena loaded: " + arena.getSpawnPedestals().size()
                + " pedestals, border " + arena.getBorderRadius() + "→" + arena.getBorderMinRadius()
                + ", world=" + (arena.getWorld() != null ? arena.getWorld().getName() : "none"));
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", arena.getWorld() != null ? arena.getWorld().getName() : null);

        var cor = arena.getCornucopiaCenter();
        if (cor != null) {
            cfg.set("arena.cornucopia.x", cor.getX());
            cfg.set("arena.cornucopia.y", cor.getY());
            cfg.set("arena.cornucopia.z", cor.getZ());
        } else {
            cfg.set("arena.cornucopia", null);
        }

        List<java.util.Map<String, Double>> serialized = new ArrayList<>();
        for (Location l : arena.getSpawnPedestals()) {
            serialized.add(java.util.Map.of(
                    "x",     l.getX(),
                    "y",     l.getY(),
                    "z",     l.getZ(),
                    "yaw",   (double) l.getYaw(),
                    "pitch", (double) l.getPitch()
            ));
        }
        cfg.set("arena.pedestals", serialized);
        cfg.set("arena.border-radius", arena.getBorderRadius());
        cfg.set("arena.border-min-radius", arena.getBorderMinRadius());
        cfg.set("arena.void-y", arena.getVoidYLevel());
        plugin.saveConfig();
    }

    public void setWorld(World w) {
        arena.setWorld(w);
        save();
    }

    public void setCornucopia(Location l) {
        arena.setCornucopiaCenter(l);
        save();
    }

    public void addPedestal(Location l) {
        arena.addSpawnPedestal(l);
        save();
    }

    public void clearPedestals() {
        arena.clearSpawnPedestals();
        save();
    }

    public void setBorder(double radius, double minRadius) {
        arena.setBorderRadius(radius);
        arena.setBorderMinRadius(minRadius);
        save();
    }

    public void setVoidY(int y) {
        arena.setVoidYLevel(y);
        save();
    }
}
