package nl.kmc.kmccore.lobbygames;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * A timed lobby parkour: touch the start marker, pass the checkpoints in order, reach the finish. The course
 * is set up with {@code /lobbygames parkour start|checkpoint|finish|clear}. Falling below the course or using
 * {@code /lobbygames parkour reset} returns you to the last checkpoint. Best times are kept per player.
 */
public final class LobbyParkour {

    private static final double REACH = 1.6;

    private static final class Run {
        long startMs;
        int next;                 // index of the next checkpoint to touch
        Location lastSafe;
        long lastBarMs;
    }

    private final KMCCore plugin;
    private final LobbyGamesManager mgr;
    private final Map<UUID, Run> runs = new HashMap<>();

    private Location start, finish;
    private final List<Location> checkpoints = new ArrayList<>();

    public LobbyParkour(KMCCore plugin, LobbyGamesManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        load();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void load() {
        YamlConfiguration d = mgr.data();
        start = readLoc(d.getString("parkour.start"));
        finish = readLoc(d.getString("parkour.finish"));
        checkpoints.clear();
        for (String s : d.getStringList("parkour.checkpoints")) {
            Location l = readLoc(s);
            if (l != null) checkpoints.add(l);
        }
    }

    private void saveCourse() {
        YamlConfiguration d = mgr.data();
        d.set("parkour.start", writeLoc(start));
        d.set("parkour.finish", writeLoc(finish));
        List<String> cps = new ArrayList<>();
        for (Location l : checkpoints) cps.add(writeLoc(l));
        d.set("parkour.checkpoints", cps);
        mgr.saveData();
    }

    static String writeLoc(Location l) {
        return l == null ? null : l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ()
                + "," + l.getYaw();
    }

    static Location readLoc(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length < 4) return null;
        var w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, 0f);
        } catch (NumberFormatException e) { return null; }
    }

    public void setStart(Location l) { start = l.clone(); saveCourse(); }
    public void setFinish(Location l) { finish = l.clone(); saveCourse(); }
    public int addCheckpoint(Location l) { checkpoints.add(l.clone()); saveCourse(); return checkpoints.size(); }
    public void clear() { start = null; finish = null; checkpoints.clear(); runs.clear(); saveCourse(); }
    public boolean isReady() { return start != null && finish != null; }
    public int checkpointCount() { return checkpoints.size(); }

    // ── Running ───────────────────────────────────────────────────────────────

    /** Called for every block-to-block move of a player (cheap distance checks only). */
    public void onMove(Player p) {
        if (!isReady() || !mgr.available(p)) {
            if (runs.remove(p.getUniqueId()) != null) { /* left the lobby: run is over */ }
            return;
        }
        Location at = p.getLocation();
        var lm = plugin.getLanguageManager();
        Run r = runs.get(p.getUniqueId());

        if (r == null) {
            if (near(at, start)) {
                r = new Run();
                r.startMs = System.currentTimeMillis();
                r.lastSafe = start.clone();
                runs.put(p.getUniqueId(), r);
                p.sendMessage(lm.tr(p, "lobbygames.parkour.started"));
                p.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - r.lastBarMs > 250) {
            r.lastBarMs = now;
            p.sendActionBar(net.kyori.adventure.text.Component.text(lm.tr(p, "lobbygames.parkour.bar",
                    fmt(now - r.startMs), r.next, checkpoints.size())));
        }

        // fell off the course
        double minY = Math.min(start.getY(), Math.min(finish.getY(),
                checkpoints.stream().mapToDouble(Location::getY).min().orElse(start.getY()))) - 12;
        if (at.getY() < minY) { reset(p); return; }

        if (r.next < checkpoints.size()) {
            if (near(at, checkpoints.get(r.next))) {
                r.lastSafe = checkpoints.get(r.next).clone();
                r.next++;
                p.sendMessage(lm.tr(p, "lobbygames.parkour.checkpoint", r.next, checkpoints.size()));
                p.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
            }
            return;
        }
        if (near(at, finish)) {
            long ms = now - r.startMs;
            runs.remove(p.getUniqueId());
            finishRun(p, ms);
        }
    }

    private void finishRun(Player p, long ms) {
        var lm = plugin.getLanguageManager();
        YamlConfiguration d = mgr.data();
        String key = "parkour.best." + p.getUniqueId();
        long best = d.getLong(key, 0);
        mgr.recordName(p.getUniqueId(), p.getName());
        boolean pb = best <= 0 || ms < best;
        if (pb) { d.set(key, ms); mgr.saveData(); }
        p.sendMessage(lm.tr(p, "lobbygames.parkour.finished", fmt(ms)));
        if (pb && best > 0) p.sendMessage(lm.tr(p, "lobbygames.parkour.pb", fmt(best - ms)));
        else if (pb) p.sendMessage(lm.tr(p, "lobbygames.parkour.first"));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    /** Back to the last checkpoint (or the start). */
    public void reset(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r == null) {
            if (start != null && mgr.available(p)) p.teleport(start);
            return;
        }
        p.teleport(r.lastSafe != null ? r.lastSafe : start);
        p.setFallDistance(0);
        p.sendMessage(plugin.getLanguageManager().tr(p, "lobbygames.parkour.reset"));
    }

    public void cancel(UUID id) { runs.remove(id); }

    private static boolean near(Location a, Location b) {
        if (b == null || a.getWorld() != b.getWorld()) return false;
        return a.distanceSquared(b) <= REACH * REACH;
    }

    static String fmt(long ms) {
        long cs = ms / 10;
        return String.format(Locale.ROOT, "%d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }
}
