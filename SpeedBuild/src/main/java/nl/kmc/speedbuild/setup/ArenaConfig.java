package nl.kmc.speedbuild.setup;

import nl.kmc.speedbuild.SpeedBuildPlugin;
import nl.kmc.speedbuild.game.BuildDefinition;
import nl.kmc.speedbuild.schematic.SchematicLoader;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted arena configuration: the world, the build anchor (player 0's slot
 * min corner), the spawn, the slot gap, and the ordered list of 10 builds.
 */
public final class ArenaConfig {

    private final SpeedBuildPlugin plugin;
    private final SchematicLoader  loader;

    private Location anchor;
    private Location spawn;
    private int      slotGap;
    private final List<BuildDefinition> builds = new ArrayList<>();

    public ArenaConfig(SpeedBuildPlugin plugin, SchematicLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
        load();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        var cfg = plugin.getConfig();
        slotGap = cfg.getInt("arena.slot-gap", 4);
        anchor  = cfg.getLocation("arena.anchor");
        spawn   = cfg.getLocation("arena.spawn");

        builds.clear();
        List<?> raw = cfg.getList("builds");
        if (raw != null) {
            int i = 0;
            for (Object o : raw) {
                if (!(o instanceof java.util.Map<?, ?> m)) continue;
                String id   = str(m.get("id"), "build" + (++i));
                String name = str(m.get("name"), id);
                String schem = str(m.get("schematic"), null);
                if (schem == null) continue;
                int    diff = m.get("difficulty") instanceof Number n ? n.intValue() : 1;
                double w    = m.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
                builds.add(new BuildDefinition(id, name, schem, diff, w));
            }
        }
    }

    public void save() {
        var cfg = plugin.getConfig();
        cfg.set("arena.slot-gap", slotGap);
        cfg.set("arena.anchor", anchor);
        cfg.set("arena.spawn", spawn);
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (BuildDefinition b : builds) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("schematic", b.schematic());
            m.put("difficulty", b.difficulty());
            m.put("weight", b.weight());
            list.add(m);
        }
        cfg.set("builds", list);
        plugin.saveConfig();
    }

    private static String str(Object o, String def) { return o != null ? o.toString() : def; }

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void setAnchor(Location l) { this.anchor = l.clone(); save(); }
    public void setSpawn(Location l)  { this.spawn  = l.clone(); save(); }
    public void setSlotGap(int g)     { this.slotGap = Math.max(0, g); save(); }

    public void addBuild(BuildDefinition def) { builds.add(def); save(); }
    public void clearBuilds()                 { builds.clear(); save(); }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Location getAnchor() { return anchor; }
    public Location getSpawn()  { return spawn; }
    public int      getSlotGap(){ return slotGap; }
    public List<BuildDefinition> getBuilds() { return builds; }

    // ── Validation ────────────────────────────────────────────────────────────

    public boolean isReady() { return issues().isEmpty(); }

    public List<String> issues() {
        List<String> out = new ArrayList<>();
        if (anchor == null) out.add("Build-anker niet ingesteld (/speedbuild anchor)");
        if (spawn  == null) out.add("Spawn niet ingesteld (/speedbuild spawn)");
        if (builds.size() != 10)
            out.add("Er moeten precies 10 builds zijn (nu: " + builds.size() + ")");
        if (!loader.isWorldEditAvailable())
            out.add("WorldEdit/FAWE niet gevonden — schematics werken niet");
        for (BuildDefinition b : builds)
            if (!loader.exists(b.schematic()))
                out.add("Schematic ontbreekt: " + b.schematic());
        return out;
    }
}
