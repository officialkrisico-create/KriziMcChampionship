package nl.kmc.kmccore.platform;

import nl.kmc.core.event.*;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clip markers for editors: every notable moment (game start/end, kills, clutch plays, records, finishes,
 * rounds) gets a timestamp relative to the stream start. Players can add their own with {@code /clip <text>};
 * admins reset the clock with {@code /clip start} when the stream goes live. Markers are appended to
 * {@code clip-markers.csv} so the editor can open it next to the recording.
 */
public final class ClipMarkers implements Listener {

    public record Marker(long timeMs, String timecode, String category, String game, String player, String label) {}

    private static final int MAX_KEPT = 500;

    private final KMCCore plugin;
    private final File file;
    private final List<Marker> markers = new ArrayList<>();
    private volatile long originMs = System.currentTimeMillis();
    private volatile String currentGame = "";

    public ClipMarkers(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clip-markers.csv");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Resets the clock to "now" (the stream went live). */
    public void startClock() {
        originMs = System.currentTimeMillis();
        add("clock", "Stream clock started", null);
    }

    public void add(String category, String label, String player) {
        if (!plugin.getConfig().getBoolean("clips.enabled", true)) return;
        long now = System.currentTimeMillis();
        Marker m = new Marker(now, timecode(now - originMs), category, currentGame, player == null ? "" : player, label);
        synchronized (markers) {
            markers.add(m);
            while (markers.size() > MAX_KEPT) markers.remove(0);
        }
        append(m);
    }

    public List<Marker> recent(int n) {
        synchronized (markers) {
            int from = Math.max(0, markers.size() - n);
            return new ArrayList<>(markers.subList(from, markers.size()));
        }
    }

    public int count() { synchronized (markers) { return markers.size(); } }

    public static String timecode(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private void append(Marker m) {
        // File IO off the main thread: a marker is never worth a hitch.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean fresh = !file.exists();
            try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                if (fresh) w.println("timecode,wallclock,category,game,player,label");
                w.println(m.timecode() + "," + java.time.Instant.ofEpochMilli(m.timeMs()) + "," + m.category() + ","
                        + csv(m.game()) + "," + csv(m.player()) + "," + csv(m.label()));
            } catch (IOException ignored) { }
        });
    }

    private static String csv(String s) {
        if (s == null) return "";
        String t = s.replaceAll("§.", "").replace("\"", "'").replace("\n", " ");
        return t.contains(",") ? "\"" + t + "\"" : t;
    }

    // ── Automatic markers ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameStart(GameStartEvent e) {
        currentGame = e.getGame().getId();
        add("game-start", "Start " + e.getGame().getDisplayName() + " (round " + e.getRound() + ")", null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameEnd(GameEndEvent e) {
        add("game-end", "End " + e.getGame().getDisplayName() + " — " + e.getWinnerDescription(), e.getMvpName());
        currentGame = "";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(KMCKillEvent e) {
        Player k = e.getKillerPlayer(), v = e.getVictimPlayer();
        if (k == null || v == null) return;
        add("kill", k.getName() + " eliminates " + v.getName(), k.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(PlayerEliminatedEvent e) {
        if (e.getPlacement() > 3 || e.getPlacement() <= 0) return;      // only the dramatic end of a game
        add("final-three", e.getPlayer().getName() + " is out in place " + e.getPlacement(), e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClutch(ClutchMomentEvent e) {
        add("clutch", e.getDescription(), e.getPlayer() != null ? e.getPlayer().getName() : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRound(RoundStartEvent e) {
        add("round", "Round " + e.getRoundNumber() + " (x" + e.getMultiplier() + ")", null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTournamentEnd(TournamentEndEvent e) {
        add("finale", "Tournament over — " + (e.getWinningTeam() != null ? e.getWinningTeam().getDisplayName() : "?"), null);
    }
}
