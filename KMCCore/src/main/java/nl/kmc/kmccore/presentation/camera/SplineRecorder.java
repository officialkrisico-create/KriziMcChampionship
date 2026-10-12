package nl.kmc.kmccore.presentation.camera;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records a camera route by flying: the position of the admin is sampled every few ticks, thinned out with
 * the Ramer-Douglas-Peucker algorithm (so a long smooth flight becomes a handful of waypoints that the
 * spline interpolates again) and saved as a normal route. Timing is preserved: a kept waypoint lasts as long
 * as the samples it replaced.
 */
public final class SplineRecorder {

    private static final class Session {
        final String routeId, description;
        final int interval;
        final List<Location> samples = new ArrayList<>();
        BukkitTask task;
        Session(String routeId, String description, int interval) {
            this.routeId = routeId; this.description = description; this.interval = interval;
        }
    }

    private final KMCCore plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public SplineRecorder(KMCCore plugin) { this.plugin = plugin; }

    public boolean isRecording(UUID id) { return sessions.containsKey(id); }

    public boolean start(Player p, String routeId, String description, int intervalTicks) {
        if (sessions.containsKey(p.getUniqueId())) return false;
        Session s = new Session(routeId, description, Math.max(2, Math.min(40, intervalTicks)));
        s.samples.add(p.getLocation().clone());
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) { cancel(p.getUniqueId()); return; }
            s.samples.add(p.getLocation().clone());
            if (s.samples.size() % 10 == 0) {
                p.sendActionBar(net.kyori.adventure.text.Component.text(
                        "§c● REC §7" + routeId + " §8| §f" + s.samples.size() + " samples §8| §f"
                                + (s.samples.size() * s.interval / 20) + "s"));
            }
        }, s.interval, s.interval);
        sessions.put(p.getUniqueId(), s);
        return true;
    }

    /** Stops, simplifies and saves. Returns the number of waypoints saved (0 = nothing recorded). */
    public int stop(Player p, double epsilonBlocks) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return -1;
        if (s.task != null) s.task.cancel();
        if (s.samples.size() < 2) return 0;

        double[][] pts = new double[s.samples.size()][];
        for (int i = 0; i < pts.length; i++) {
            Location l = s.samples.get(i);
            pts[i] = new double[] {l.getX(), l.getY(), l.getZ()};
        }
        List<Integer> keep = simplifyIndices(pts, epsilonBlocks);

        var cm = plugin.getCinematicManager();
        cm.startRecording(p.getUniqueId(), s.routeId, s.description);
        int prevIdx = 0;
        for (int k = 0; k < keep.size(); k++) {
            int idx = keep.get(k);
            int ticks = k == 0 ? 20 : Math.max(10, (idx - prevIdx) * s.interval);
            prevIdx = idx;
            cm.addWaypoint(p.getUniqueId(), new CameraWaypoint(s.samples.get(idx), ticks,
                    InterpolationType.SMOOTH, "", "", ""));
        }
        cm.saveRecording(p.getUniqueId());
        return keep.size();
    }

    public void cancel(UUID id) {
        Session s = sessions.remove(id);
        if (s != null && s.task != null) s.task.cancel();
    }

    public void shutdown() {
        for (Session s : sessions.values()) if (s.task != null) s.task.cancel();
        sessions.clear();
    }

    // ── Pure maths (unit-tested) ──────────────────────────────────────────────

    /** Indices of the points to keep (always the first and the last), Ramer-Douglas-Peucker in 3D. */
    public static List<Integer> simplifyIndices(double[][] pts, double epsilon) {
        int n = pts.length;
        List<Integer> out = new ArrayList<>();
        if (n == 0) return out;
        if (n <= 2) { for (int i = 0; i < n; i++) out.add(i); return out; }
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        rdp(pts, 0, n - 1, epsilon, keep);
        for (int i = 0; i < n; i++) if (keep[i]) out.add(i);
        return out;
    }

    private static void rdp(double[][] p, int a, int b, double eps, boolean[] keep) {
        // iterative to survive very long recordings
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[] {a, b});
        while (!stack.isEmpty()) {
            int[] r = stack.pop();
            int lo = r[0], hi = r[1];
            if (hi <= lo + 1) continue;
            double maxD = -1;
            int idx = -1;
            for (int i = lo + 1; i < hi; i++) {
                double d = distToSegment(p[i], p[lo], p[hi]);
                if (d > maxD) { maxD = d; idx = i; }
            }
            if (maxD > eps && idx > 0) {
                keep[idx] = true;
                stack.push(new int[] {lo, idx});
                stack.push(new int[] {idx, hi});
            }
        }
    }

    static double distToSegment(double[] p, double[] a, double[] b) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 == 0 ? 0 : ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby + (p[2] - a[2]) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double dx = p[0] - (a[0] + t * abx), dy = p[1] - (a[1] + t * aby), dz = p[2] - (a[2] + t * abz);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
