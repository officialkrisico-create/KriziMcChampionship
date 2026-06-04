package nl.kmc.kmccore.presentation.camera;

import nl.kmc.kmccore.presentation.CinematicState;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Logger;

/**
 * Plays a {@link CameraRoute} for a set of players.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>Saves each player's state ({@link CinematicState}).
 *   <li>Switches players to {@link GameMode#SPECTATOR} so they can be freely teleported.
 *   <li>Schedules a repeating task (every {@value #TICK_INTERVAL} ticks) that moves
 *       the camera along the route via interpolation.
 *   <li>Sends title/subtitle/actionbar overlays at each waypoint transition.
 *   <li>On completion (or cancellation), restores all player states and calls the callback.
 * </ol>
 */
public final class CameraController {

    /** Ticks between each camera position update. Lower = smoother but more work. */
    private static final int TICK_INTERVAL = 2;

    private static final Logger LOG = Logger.getLogger(CameraController.class.getName());

    private final JavaPlugin plugin;
    private final CameraRoute route;
    private final List<Player> players;
    private final Runnable onComplete;

    private final Map<UUID, CinematicState> savedStates = new HashMap<>();

    private BukkitTask task;
    private boolean    cancelled = false;

    // Playhead state
    private int segmentIndex = 0;
    private int tickInSegment = 0;

    public CameraController(JavaPlugin plugin, CameraRoute route,
                            Collection<Player> players, Runnable onComplete) {
        this.plugin     = plugin;
        this.route      = route;
        this.players    = new ArrayList<>(players);
        this.onComplete = onComplete;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Begins playback. Must be called from the main thread. */
    public void start() {
        if (route.isEmpty()) {
            LOG.warning("[Cinematic] Route '" + route.getId() + "' has no waypoints — skipping.");
            finish();
            return;
        }

        // Save state and freeze players
        players.removeIf(p -> !p.isOnline());
        for (Player p : players) {
            savedStates.put(p.getUniqueId(), CinematicState.capture(p));
            p.setGameMode(GameMode.SPECTATOR);
            p.sendTitle("", "", 0, 1, 0); // clear any existing title
        }

        // Send initial overlay for first waypoint
        List<CameraWaypoint> wps = route.getWaypoints();
        if (!wps.isEmpty()) sendOverlay(wps.get(0));

        // Schedule repeating tick
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tick, 0L, TICK_INTERVAL);
    }

    /**
     * Cancels the cinematic immediately and restores all players.
     * Safe to call at any time.
     */
    public void cancel() {
        cancelled = true;
        if (task != null) { task.cancel(); task = null; }
        restoreAll();
        if (onComplete != null) onComplete.run();
    }

    /** Removes a player from the active cinematic (e.g. on disconnect). */
    public void removePlayer(UUID uuid) {
        players.removeIf(p -> p.getUniqueId().equals(uuid));
        CinematicState state = savedStates.remove(uuid);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && state != null) state.restore(p);
    }

    public boolean isActive()     { return task != null && !cancelled; }
    public String  getRouteId()   { return route.getId(); }
    public List<Player> getPlayers() { return Collections.unmodifiableList(players); }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private void tick() {
        if (cancelled) return;

        List<CameraWaypoint> wps = route.getWaypoints();

        // Need at least 2 waypoints to form a segment
        if (wps.size() < 2) { finish(); return; }

        // Route is done when we've passed the last segment
        if (segmentIndex >= wps.size() - 1) { finish(); return; }

        CameraWaypoint from = wps.get(segmentIndex);
        CameraWaypoint to   = wps.get(segmentIndex + 1);

        int segmentTicks = to.getDurationTicks();
        float t = (float) tickInSegment / segmentTicks;
        t = Math.min(t, 1.0f);

        Location pos = from.interpolateTo(to, t);

        // Remove players who logged off
        players.removeIf(p -> !p.isOnline());

        for (Player p : players) {
            if (!p.getGameMode().equals(GameMode.SPECTATOR)) {
                p.setGameMode(GameMode.SPECTATOR);
            }
            p.teleport(pos);
        }

        tickInSegment += TICK_INTERVAL;

        if (tickInSegment >= segmentTicks) {
            // Advance to next segment
            segmentIndex++;
            tickInSegment = 0;

            // Send overlay for the NEW destination waypoint if there is one
            if (segmentIndex < wps.size() - 1) {
                sendOverlay(wps.get(segmentIndex + 1));
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void finish() {
        if (task != null) { task.cancel(); task = null; }
        if (!cancelled) {
            restoreAll();
            if (onComplete != null) onComplete.run();
        }
    }

    private void restoreAll() {
        for (Player p : new ArrayList<>(players)) {
            CinematicState state = savedStates.get(p.getUniqueId());
            if (state != null && p.isOnline()) state.restore(p);
        }
        savedStates.clear();
    }

    private void sendOverlay(CameraWaypoint wp) {
        String title    = colorize(wp.getTitle());
        String subtitle = colorize(wp.getSubtitle());
        String ab       = colorize(wp.getActionBar());

        for (Player p : players) {
            if (!p.isOnline()) continue;
            if (!title.isBlank() || !subtitle.isBlank()) {
                // stay=duration so the title doesn't disappear until the next waypoint
                int stayTicks = wp.getDurationTicks();
                p.sendTitle(title, subtitle, 5, stayTicks, 5);
            }
            if (!ab.isBlank()) {
                p.sendActionBar(net.kyori.adventure.text.Component.text(ab));
            }
        }
    }

    private static String colorize(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
