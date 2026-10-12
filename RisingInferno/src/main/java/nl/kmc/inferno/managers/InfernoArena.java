package nl.kmc.inferno.managers;

import nl.kmc.inferno.RisingInfernoPlugin;
import nl.kmc.inferno.model.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * The tower: lava area, where the lava starts, the finish box, start positions, bonus orbs and an optional
 * spectator spot. Stored under {@code arena:} in config.yml.
 */
public final class InfernoArena {

    private final RisingInfernoPlugin plugin;

    private World world;
    private Box lavaArea;            // only x/z matter (y is ignored)
    private Integer lavaBaseY;       // top of the lava at the start
    private Box finish;
    private final List<Location> spawns = new ArrayList<>();
    private final List<Location> orbs = new ArrayList<>();
    private Location spectator;
    private int riseSeconds;         // 0 = use game.rise-seconds

    // pos1/pos2 clipboard of the admin commands: [0]=lava pos1, [1]=lava pos2, [2]=finish pos1, [3]=finish pos2
    private final Location[] picks = new Location[4];

    public InfernoArena(RisingInfernoPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        var cfg = plugin.getConfig();
        String w = cfg.getString("arena.world", "");
        world = w != null && !w.isBlank() ? Bukkit.getWorld(w) : null;
        lavaArea = box(cfg.getConfigurationSection("arena.lava-area"));
        lavaBaseY = cfg.isSet("arena.lava-base-y") ? cfg.getInt("arena.lava-base-y") : null;
        finish = box(cfg.getConfigurationSection("arena.finish"));
        riseSeconds = cfg.getInt("arena.rise-seconds", 0);
        spawns.clear();
        orbs.clear();
        for (Object o : cfg.getList("arena.spawns", List.of())) {
            Location l = loc(o);
            if (l != null) spawns.add(l);
        }
        for (Object o : cfg.getList("arena.orbs", List.of())) {
            Location l = loc(o);
            if (l != null) orbs.add(l);
        }
        spectator = loc(cfg.getConfigurationSection("arena.spectator") != null
                ? cfg.getConfigurationSection("arena.spectator").getValues(false) : null);
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.world", world != null ? world.getName() : "");
        setBox(cfg, "arena.lava-area", lavaArea);
        cfg.set("arena.lava-base-y", lavaBaseY);
        setBox(cfg, "arena.finish", finish);
        cfg.set("arena.rise-seconds", riseSeconds);
        cfg.set("arena.spawns", spawns.stream().map(InfernoArena::map).toList());
        cfg.set("arena.orbs", orbs.stream().map(InfernoArena::map).toList());
        cfg.set("arena.spectator", spectator != null ? map(spectator) : null);
        plugin.saveConfig();
    }

    private static java.util.Map<String, Object> map(Location l) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
        m.put("yaw", (double) l.getYaw()); m.put("pitch", (double) l.getPitch());
        return m;
    }

    private Location loc(Object o) {
        if (!(o instanceof java.util.Map<?, ?> m) || world == null) return null;
        try {
            return new Location(world, ((Number) m.get("x")).doubleValue(), ((Number) m.get("y")).doubleValue(),
                    ((Number) m.get("z")).doubleValue(),
                    m.get("yaw") instanceof Number y ? y.floatValue() : 0f,
                    m.get("pitch") instanceof Number p ? p.floatValue() : 0f);
        } catch (Exception e) { return null; }
    }

    private static Box box(ConfigurationSection s) {
        if (s == null || !s.isSet("x1")) return null;
        return Box.of(s.getInt("x1"), s.getInt("y1"), s.getInt("z1"), s.getInt("x2"), s.getInt("y2"), s.getInt("z2"));
    }

    private static void setBox(org.bukkit.configuration.file.FileConfiguration cfg, String path, Box b) {
        if (b == null) { cfg.set(path, null); return; }
        cfg.set(path + ".x1", b.minX()); cfg.set(path + ".y1", b.minY()); cfg.set(path + ".z1", b.minZ());
        cfg.set(path + ".x2", b.maxX()); cfg.set(path + ".y2", b.maxY()); cfg.set(path + ".z2", b.maxZ());
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    /** kind: 0 = lava pos1, 1 = lava pos2, 2 = finish pos1, 3 = finish pos2. Applies when both of a pair are set. */
    public void pick(int kind, Location l) {
        world = l.getWorld();
        picks[kind] = l.getBlock().getLocation();
        if (kind < 2 && picks[0] != null && picks[1] != null) {
            lavaArea = Box.of(picks[0].getBlockX(), picks[0].getBlockY(), picks[0].getBlockZ(),
                    picks[1].getBlockX(), picks[1].getBlockY(), picks[1].getBlockZ());
        }
        if (kind >= 2 && picks[2] != null && picks[3] != null) {
            finish = Box.of(picks[2].getBlockX(), picks[2].getBlockY(), picks[2].getBlockZ(),
                    picks[3].getBlockX(), picks[3].getBlockY(), picks[3].getBlockZ());
        }
        save();
    }

    public void setLavaBase(Location standing) {
        world = standing.getWorld();
        lavaBaseY = standing.getBlockY();
        save();
    }

    public void setRiseSeconds(int s) { riseSeconds = Math.max(0, s); save(); }

    public void addSpawn(Location l) { world = l.getWorld(); spawns.add(l.clone()); save(); }
    public void clearSpawns() { spawns.clear(); save(); }
    public void addOrb(Location l) { world = l.getWorld(); orbs.add(l.clone()); save(); }
    public void clearOrbs() { orbs.clear(); save(); }
    public void setSpectator(Location l) { spectator = l != null ? l.clone() : null; save(); }

    /** Replaces everything with a freshly built arena (used by /kmctest). */
    public void configure(World w, Box lava, int baseY, Box fin, List<Location> spawnList, List<Location> orbList,
                          Location spec, int rise) {
        world = w;
        lavaArea = lava;
        lavaBaseY = baseY;
        finish = fin;
        spawns.clear();
        spawns.addAll(spawnList);
        orbs.clear();
        orbs.addAll(orbList);
        spectator = spec;
        riseSeconds = rise;
        save();
    }

    // ── Access ────────────────────────────────────────────────────────────────

    public World getWorld() { return world; }
    public Box getLavaArea() { return lavaArea; }
    public Integer getLavaBaseY() { return lavaBaseY; }
    public Box getFinish() { return finish; }
    public List<Location> getSpawns() { return spawns; }
    public List<Location> getOrbs() { return orbs; }
    public int getRiseSeconds() { return riseSeconds; }

    /** Y of the finish floor (the block row the finish box starts on). */
    public double finishFloorY() { return finish != null ? finish.minY() : 0; }

    public Location spectatorSpawn() {
        if (spectator != null) return spectator.clone();
        if (finish != null && world != null) {
            return new Location(world, (finish.minX() + finish.maxX() + 1) / 2.0, finish.maxY() + 3,
                    (finish.minZ() + finish.maxZ() + 1) / 2.0);
        }
        return spawns.isEmpty() ? null : spawns.get(0).clone().add(0, 10, 0);
    }

    /** Centre of the lava area at finish height (camera focus for the flyover). */
    public Location centre() {
        if (world == null || lavaArea == null) return spawns.isEmpty() ? null : spawns.get(0).clone();
        double y = finish != null ? finish.minY() : (lavaBaseY != null ? lavaBaseY + 10 : 80);
        return new Location(world, (lavaArea.minX() + lavaArea.maxX() + 1) / 2.0, y,
                (lavaArea.minZ() + lavaArea.maxZ() + 1) / 2.0);
    }

    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (world == null) p.add("geen wereld");
        if (lavaArea == null) p.add("lava-gebied niet gezet (/inferno lava pos1 + pos2)");
        if (lavaBaseY == null) p.add("lava-startniveau niet gezet (/inferno lava base)");
        if (finish == null) p.add("finish niet gezet (/inferno finish pos1 + pos2)");
        if (spawns.isEmpty()) p.add("geen startposities (/inferno spawn add)");
        if (finish != null && lavaBaseY != null && finish.minY() <= lavaBaseY)
            p.add("de finish ligt niet boven het lava-startniveau");
        return p;
    }

    public boolean isReady() { return problems().isEmpty(); }
}
