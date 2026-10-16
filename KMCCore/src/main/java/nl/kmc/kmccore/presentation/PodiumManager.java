package nl.kmc.kmccore.presentation;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The physical podium of the winner ceremony: three columns (gold = 1st, iron = 2nd, copper = 3rd) that an
 * admin builds with {@code /kmcpodium set}. During the ceremony the members of the teams that finished on the
 * podium are put on top of their column, looking at the audience, and kept there until the ceremony is over.
 * Everybody else watches through a camera in front of it.
 */
public final class PodiumManager {

    private static final String CAMERA_KEY = "winner-podium";

    private final KMCCore plugin;
    private final File file;
    private Location center;                 // the middle of the 1st column, at floor level; yaw = faces the audience
    private final Map<UUID, Location> originals = new HashMap<>();
    private final List<BukkitTask> holdTasks = new ArrayList<>();

    public PodiumManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "podium.yml");
        load();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    public boolean isSet() { return center != null && center.getWorld() != null; }

    public Location getCenter() { return center == null ? null : center.clone(); }

    /** Builds the podium with its middle at {@code where}, facing where {@code where} looks. */
    public void set(Location where) {
        if (isSet()) clearBlocks();
        center = where.getBlock().getLocation().add(0.5, 0, 0.5);
        center.setYaw(where.getYaw());
        center.setPitch(0f);
        build();
        save();
    }

    public boolean remove() {
        if (center == null) return false;
        clearBlocks();
        center = null;
        save();
        return true;
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private static final int[] HEIGHT = {0, 3, 2, 1};                 // by place
    private static final double[] SIDE = {0, 0, -3.2, 3.2};           // by place; the audience's left is -right

    private Location columnBase(int place) {
        double r = Math.toRadians(center.getYaw());
        double rx = Math.cos(r), rz = Math.sin(r);
        return center.clone().add(rx * SIDE[place], 0, rz * SIDE[place]);
    }

    /** How far (sideways) the host stands from the middle: just outside the 2nd-place column. */
    private static final double HOST_SIDE = -6.6;

    /** Where the host stands when it presents here: beside the podium, on the floor, facing the audience. */
    public Location hostSpot() {
        if (!isSet()) return null;
        double r = Math.toRadians(center.getYaw());
        Location l = center.clone().add(Math.cos(r) * HOST_SIDE, 0, Math.sin(r) * HOST_SIDE);
        l.setYaw(center.getYaw());
        l.setPitch(0f);
        return l;
    }

    /**
     * Where the host stands during the team showcase: in the middle, behind the team, on the back edge of the
     * gold column (the teams use the front rows), facing the audience.
     */
    public Location showcaseHostSpot() {
        Location top = spot(1);
        if (top == null) return null;
        double r = Math.toRadians(center.getYaw());
        double fx = -Math.sin(r), fz = Math.cos(r);
        Location l = top.clone().add(-fx * 1.3, 0, -fz * 1.3);
        l.setYaw(center.getYaw());
        l.setPitch(0f);
        return l;
    }

    /**
     * Places for a whole team on top of the 1st-place column (team showcase): in rows of up to three, centred,
     * everybody facing the audience. Empty when there is no podium or the team is larger than the top fits (9).
     */
    public List<Location> showcaseSlots(int n) {
        Location top = spot(1);
        if (top == null || n < 1 || n > 9) return List.of();
        int cols = n <= 3 ? n : (n == 4 ? 2 : 3);
        int rows = (n + cols - 1) / cols;
        double r = Math.toRadians(center.getYaw());
        double rx = Math.cos(r), rz = Math.sin(r);          // to the right of the line-up
        double fx = -Math.sin(r), fz = Math.cos(r);         // towards the audience
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int row = i / cols;
            int inRow = row < rows - 1 ? cols : n - cols * (rows - 1);
            int col = i % cols;
            double dx = (col - (inRow - 1) / 2.0) * 1.1;
            double df = ((rows - 1) / 2.0 - row) * 0.9;     // the first row stands in front
            Location l = top.clone().add(rx * dx + fx * df, 0, rz * dx + fz * df);
            l.setYaw(center.getYaw());
            l.setPitch(0f);
            out.add(l);
        }
        return out;
    }

    /** Where a player of {@code place} stands: on top of the column. */
    public Location spot(int place) {
        if (!isSet() || place < 1 || place > 3) return null;
        Location l = columnBase(place);
        l.add(0, HEIGHT[place], 0);
        l.setYaw(center.getYaw());
        l.setPitch(0f);
        return l;
    }

    private void build() {
        for (int place = 1; place <= 3; place++) {
            Material m = place == 1 ? Material.GOLD_BLOCK : place == 2 ? Material.IRON_BLOCK : Material.COPPER_BLOCK;
            fill(place, m);
        }
    }

    private void clearBlocks() {
        for (int place = 1; place <= 3; place++) fill(place, Material.AIR);
    }

    private void fill(int place, Material m) {
        Location b = columnBase(place);
        if (b.getWorld() == null) return;
        int bx = b.getBlockX(), by = b.getBlockY(), bz = b.getBlockZ();
        // 3 x 3 footprint (axis-aligned around the column middle)
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int h = 0; h < HEIGHT[place]; h++)
                    b.getWorld().getBlockAt(bx + dx, by + h, bz + dz).setType(m, false);
    }

    // ── During the ceremony ───────────────────────────────────────────────────

    /** Puts the online members of {@code team} on the column of {@code place} and keeps them there. */
    public void place(KMCTeam team, int place) {
        Location spot = spot(place);
        if (spot == null || team == null) return;
        List<Player> members = new ArrayList<>();
        for (UUID id : team.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) members.add(p);
        }
        // up to 4 players fit on the 3 x 3 top: the corners first, then the middle
        double[][] offsets = {{-0.8, -0.8}, {0.8, -0.8}, {-0.8, 0.8}, {0.8, 0.8}, {0, 0}, {0, -1.2}, {0, 1.2}, {-1.2, 0}};
        Map<UUID, Location> mine = new HashMap<>();
        for (int i = 0; i < members.size(); i++) {
            Player p = members.get(i);
            double[] o = offsets[i % offsets.length];
            Location l = spot.clone().add(o[0], 0, o[1]);
            originals.putIfAbsent(p.getUniqueId(), p.getLocation());
            p.teleport(l);
            mine.put(p.getUniqueId(), l);
        }
        float yaw = center.getYaw();
        BukkitTask hold = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : members) {
                Location want = mine.get(p.getUniqueId());
                if (want == null || !p.isOnline()) continue;
                Location now = p.getLocation();
                if (now.getWorld() != want.getWorld() || now.distanceSquared(want) > 1.2) { p.teleport(want); continue; }
                float d = Math.abs(((now.getYaw() - yaw + 540f) % 360f) - 180f);
                if (d > 5f) p.setRotation(yaw, 0f);
            }
        }, 4L, 4L);
        holdTasks.add(hold);
    }

    /** Everybody who is not on the podium watches from in front of it, for {@code ticks} ticks. */
    public void watch(Collection<? extends Player> viewers, int ticks) {
        var cm = plugin.getCinematicManager();
        if (cm == null || !isSet() || viewers.isEmpty()) return;
        double r = Math.toRadians(center.getYaw());
        double fx = -Math.sin(r), fz = Math.cos(r);
        // the picture includes the host beside the podium, so the camera aims a little towards its side
        double sx = Math.cos(r) * HOST_SIDE * 0.14, sz = Math.sin(r) * HOST_SIDE * 0.14;
        Location target = center.clone().add(sx, 2.2, sz);
        Location far = center.clone().add(sx + fx * 14.5, 4.0, sz + fz * 14.5);
        Location near = center.clone().add(sx + fx * 11, 3.2, sz + fz * 11);
        far.setDirection(target.toVector().subtract(far.toVector()));
        near.setDirection(target.toVector().subtract(near.toVector()));
        CameraRoute route = new CameraRoute(CAMERA_KEY, "Winner podium");
        route.addWaypoint(new CameraWaypoint(far, Math.max(20, ticks * 3 / 5), InterpolationType.SMOOTH, "", "", ""));
        route.addWaypoint(new CameraWaypoint(near, Math.max(20, ticks * 2 / 5), InterpolationType.SMOOTH, "", "", ""));
        route.addWaypoint(new CameraWaypoint(near, 20, InterpolationType.SMOOTH, "", "", ""));
        cm.playRouteInstance(CAMERA_KEY, route, new ArrayList<>(viewers), false, null);
    }

    /** The ceremony is over: camera off, everybody back where they were. */
    public void finish() {
        holdTasks.forEach(BukkitTask::cancel);
        holdTasks.clear();
        var cm = plugin.getCinematicManager();
        if (cm != null) cm.stopRoute(CAMERA_KEY);
        for (Map.Entry<UUID, Location> e : originals.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) p.teleport(e.getValue());
        }
        originals.clear();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!file.isFile()) return;
        center = YamlConfiguration.loadConfiguration(file).getLocation("center");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (center != null) y.set("center", center);
        try { y.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Could not save podium.yml: " + e.getMessage()); }
    }
}
