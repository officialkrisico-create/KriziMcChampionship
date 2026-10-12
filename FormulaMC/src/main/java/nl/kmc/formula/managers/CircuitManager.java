package nl.kmc.formula.managers;

import nl.kmc.formula.FormulaMCPlugin;
import nl.kmc.formula.models.Circuit;
import nl.kmc.formula.models.Gate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Loads/saves circuits from {@code config.yml} and keeps the admins' pos1/pos2 selections. */
public final class CircuitManager {

    private final FormulaMCPlugin plugin;
    private final Map<String, Circuit> circuits = new LinkedHashMap<>();
    private String selectedId = "";

    /** pos1/pos2 selections per admin (block positions). */
    private final Map<UUID, Location[]> selections = new java.util.HashMap<>();

    public CircuitManager(FormulaMCPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        circuits.clear();
        var cfg = plugin.getConfig();
        selectedId = cfg.getString("selected", "");
        ConfigurationSection root = cfg.getConfigurationSection("circuits");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Circuit c = new Circuit(id, s.getString("name", id));
            World w = Bukkit.getWorld(s.getString("world", ""));
            c.setWorld(w);
            c.setLaps(s.getInt("laps", 3));
            for (Object o : s.getList("spawns", List.of())) {
                if (!(o instanceof Map<?, ?> m) || w == null) continue;
                try {
                    c.getSpawns().add(new Location(w, num(m, "x"), num(m, "y"), num(m, "z"),
                            (float) num(m, "yaw"), (float) num(m, "pitch")));
                } catch (Exception ignored) { }
            }
            Gate line = gate(s.getConfigurationSection("line"));
            if (line != null) c.setLine(line);
            for (Object o : s.getList("checkpoints", List.of())) {
                if (o instanceof Map<?, ?> m) {
                    try { c.getCheckpoints().add(gateFromMap(m)); } catch (Exception ignored) { }
                }
            }
            for (Object o : s.getList("pads", List.of())) {
                if (o instanceof Map<?, ?> m) {
                    try { c.getBoostPads().add(gateFromMap(m)); } catch (Exception ignored) { }
                }
            }
            for (Object o : s.getList("boxes", List.of())) {
                if (!(o instanceof Map<?, ?> m) || w == null) continue;
                try { c.getItemBoxes().add(new Location(w, num(m, "x"), num(m, "y"), num(m, "z"))); }
                catch (Exception ignored) { }
            }
            ConfigurationSection r = s.getConfigurationSection("reset");
            if (r != null && w != null) {
                c.setReset(new Location(w, r.getDouble("x"), r.getDouble("y"), r.getDouble("z"),
                        (float) r.getDouble("yaw"), 0f));
            }
            circuits.put(id.toLowerCase(), c);
        }
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("selected", selectedId);
        cfg.set("circuits", null);
        for (Circuit c : circuits.values()) {
            String p = "circuits." + c.getId() + ".";
            cfg.set(p + "name", c.getDisplayName());
            cfg.set(p + "world", c.getWorld() != null ? c.getWorld().getName() : "");
            cfg.set(p + "laps", c.getLaps());
            List<Map<String, Object>> sp = new ArrayList<>();
            for (Location l : c.getSpawns()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
                m.put("yaw", (double) l.getYaw()); m.put("pitch", (double) l.getPitch());
                sp.add(m);
            }
            cfg.set(p + "spawns", sp);
            if (c.getLine() != null) cfg.set(p + "line", toMap(c.getLine()));
            List<Map<String, Object>> cps = new ArrayList<>();
            for (Gate g : c.getCheckpoints()) cps.add(toMap(g));
            cfg.set(p + "checkpoints", cps);
            List<Map<String, Object>> pads = new ArrayList<>();
            for (Gate g : c.getBoostPads()) pads.add(toMap(g));
            cfg.set(p + "pads", pads);
            List<Map<String, Object>> boxes = new ArrayList<>();
            for (Location l : c.getItemBoxes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
                boxes.add(m);
            }
            cfg.set(p + "boxes", boxes);
            if (c.getReset() != null) {
                Location r = c.getReset();
                cfg.set(p + "reset.x", r.getX());
                cfg.set(p + "reset.y", r.getY());
                cfg.set(p + "reset.z", r.getZ());
                cfg.set(p + "reset.yaw", (double) r.getYaw());
            }
        }
        plugin.saveConfig();
    }

    private static double num(Map<?, ?> m, String k) { return ((Number) m.get(k)).doubleValue(); }

    private static Map<String, Object> toMap(Gate g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", g.getWorld());
        m.put("x1", g.minX()); m.put("y1", g.minY()); m.put("z1", g.minZ());
        m.put("x2", g.maxX()); m.put("y2", g.maxY()); m.put("z2", g.maxZ());
        return m;
    }

    private static Gate gateFromMap(Map<?, ?> m) {
        return new Gate(String.valueOf(m.get("world")),
                (int) num(m, "x1"), (int) num(m, "y1"), (int) num(m, "z1"),
                (int) num(m, "x2"), (int) num(m, "y2"), (int) num(m, "z2"));
    }

    private static Gate gate(ConfigurationSection s) {
        if (s == null) return null;
        return new Gate(s.getString("world", "world"),
                s.getInt("x1"), s.getInt("y1"), s.getInt("z1"), s.getInt("x2"), s.getInt("y2"), s.getInt("z2"));
    }

    // ── Access ────────────────────────────────────────────────────────────────

    public Map<String, Circuit> all()          { return circuits; }
    public Circuit get(String id)              { return id == null ? null : circuits.get(id.toLowerCase()); }
    public String  getSelectedId()             { return selectedId; }

    public Circuit create(String id, String name, World world) {
        Circuit c = new Circuit(id, name);
        c.setWorld(world);
        circuits.put(id.toLowerCase(), c);
        selectedId = id;
        save();
        return c;
    }

    public boolean delete(String id) {
        boolean removed = circuits.remove(id.toLowerCase()) != null;
        if (removed) {
            if (selectedId.equalsIgnoreCase(id)) selectedId = "";
            save();
        }
        return removed;
    }

    public void select(String id) { this.selectedId = id; save(); }

    /** The circuit for the next race: the selected one if ready, else a random ready circuit. */
    public Circuit pick() {
        Circuit sel = get(selectedId);
        if (sel != null && sel.isReady()) return sel;
        List<Circuit> ready = new ArrayList<>();
        for (Circuit c : circuits.values()) if (c.isReady()) ready.add(c);
        if (ready.isEmpty()) return null;
        return ready.get(new Random().nextInt(ready.size()));
    }

    public Location[] selection(UUID admin) {
        return selections.computeIfAbsent(admin, k -> new Location[2]);
    }
}
