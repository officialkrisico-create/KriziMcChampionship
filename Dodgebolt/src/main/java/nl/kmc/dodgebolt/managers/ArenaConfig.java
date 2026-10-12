package nl.kmc.dodgebolt.managers;

import nl.kmc.dodgebolt.DodgeboltPlugin;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dodgebolt arena as configured by {@code /dodgebolt}: per side a list of spawn points and an
 * arrow-supply point, plus a spectator spot, a centre (flyover) and the void level.
 * Everything is stored in the plugin's config.yml under {@code arena.*}.
 */
public final class ArenaConfig {

    public enum Side {
        A, B;
        public String key() { return this == A ? "side-a" : "side-b"; }
        public Side other() { return this == A ? B : A; }
    }

    private final DodgeboltPlugin plugin;

    public ArenaConfig(DodgeboltPlugin plugin) { this.plugin = plugin; }

    // ── Reading ───────────────────────────────────────────────────────────────

    public List<Location> spawns(Side side) {
        List<Location> out = new ArrayList<>();
        for (Object o : plugin.getConfig().getList("arena." + side.key() + ".spawns", List.of())) {
            if (o instanceof Location l && l.getWorld() != null) out.add(l);
        }
        return out;
    }

    /** Where this side's supply arrows appear: the explicit drop point, else the average of its spawns. */
    public Location drop(Side side) {
        Location l = plugin.getConfig().getLocation("arena." + side.key() + ".drop");
        if (l != null && l.getWorld() != null) return l;
        List<Location> sp = spawns(side);
        if (sp.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location s : sp) { x += s.getX(); y += s.getY(); z += s.getZ(); }
        return new Location(sp.get(0).getWorld(), x / sp.size(), y / sp.size() + 0.5, z / sp.size());
    }

    public Location spectator() {
        Location l = plugin.getConfig().getLocation("arena.spectator");
        return l != null && l.getWorld() != null ? l : null;
    }

    /** The middle of the arena: explicit centre, else halfway between the two platforms. */
    public Location center() {
        Location l = plugin.getConfig().getLocation("arena.center");
        if (l != null && l.getWorld() != null) return l;
        Location a = drop(Side.A), b = drop(Side.B);
        if (a == null || b == null || a.getWorld() != b.getWorld()) return a != null ? a : b;
        return new Location(a.getWorld(), (a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2 + 2, (a.getZ() + b.getZ()) / 2);
    }

    public double voidY() {
        double cfg = plugin.getConfig().getDouble("game.void-y", 0);
        if (cfg != 0) return cfg;
        double lowest = Double.MAX_VALUE;
        for (Side s : Side.values()) for (Location l : spawns(s)) lowest = Math.min(lowest, l.getY());
        return lowest == Double.MAX_VALUE ? -64 : lowest - 12;
    }

    public World world() {
        List<Location> a = spawns(Side.A);
        return a.isEmpty() ? null : a.get(0).getWorld();
    }

    /** Problems that make the arena unplayable (empty when ready). */
    public List<String> errors() {
        List<String> out = new ArrayList<>();
        List<Location> a = spawns(Side.A), b = spawns(Side.B);
        if (a.isEmpty()) out.add("Kant A heeft geen spawns (/dodgebolt spawn a)");
        if (b.isEmpty()) out.add("Kant B heeft geen spawns (/dodgebolt spawn b)");
        if (!a.isEmpty() && !b.isEmpty() && a.get(0).getWorld() != b.get(0).getWorld())
            out.add("Kant A en B staan in verschillende werelden");
        return out;
    }

    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (spawns(Side.A).size() < 4 && !spawns(Side.A).isEmpty())
            out.add("Kant A heeft maar " + spawns(Side.A).size() + " spawn(s) — teams van 4 delen een plek");
        if (spawns(Side.B).size() < 4 && !spawns(Side.B).isEmpty())
            out.add("Kant B heeft maar " + spawns(Side.B).size() + " spawn(s) — teams van 4 delen een plek");
        if (spectator() == null) out.add("Geen toeschouwerspot (/dodgebolt spectator) — het midden wordt gebruikt");
        return out;
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    public void addSpawn(Side side, Location loc) {
        List<Location> list = spawns(side);
        list.add(loc);
        plugin.getConfig().set("arena." + side.key() + ".spawns", list);
        plugin.saveConfig();
    }

    public void clearSpawns(Side side) {
        plugin.getConfig().set("arena." + side.key() + ".spawns", new ArrayList<Location>());
        plugin.saveConfig();
    }

    public void setDrop(Side side, Location loc) {
        plugin.getConfig().set("arena." + side.key() + ".drop", loc);
        plugin.saveConfig();
    }

    public void setSpectator(Location loc) {
        plugin.getConfig().set("arena.spectator", loc);
        plugin.saveConfig();
    }

    public void setCenter(Location loc) {
        plugin.getConfig().set("arena.center", loc);
        plugin.saveConfig();
    }

    public void setVoidY(double y) {
        plugin.getConfig().set("game.void-y", y);
        plugin.saveConfig();
    }
}
