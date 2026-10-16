package nl.kmc.kmccore.presentation.camera;

import nl.kmc.kmccore.presentation.CinematicState;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Logger;

/**
 * Plays a {@link CameraRoute} for a set of players, replay-mod style.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>Saves each player's state ({@link CinematicState}) and switches them to {@link GameMode#SPECTATOR}.
 *   <li>Every tick the camera position is sampled from a {@link SplinePath} (continuous velocity through all
 *       waypoints, easing only at the very start and end).
 *   <li><b>Smooth mode</b> (default): players spectate an invisible {@link ItemDisplay} that is teleported
 *       every tick with a client-side teleport duration, so the client interpolates the camera at its own
 *       frame rate instead of snapping 20 times per second. If spectating does not stick for a player, that
 *       player automatically falls back to plain per-tick teleports.
 *   <li>Title/subtitle/actionbar overlays are sent as the camera passes the waypoints.
 *   <li>On completion (or cancellation), all player states are restored and the callback runs.
 * </ol>
 */
public final class CameraController {

    /** Ticks of speed ramp at the very start and end of a route. */
    private static final double EASE_TICKS = 24.0;
    /** Client-side interpolation window for the camera entity, in ticks. */
    private static final int TELEPORT_DURATION = 3;
    /** After this many failed re-attaches a player falls back to direct teleports. */
    private static final int MAX_ATTACH_MISSES = 8;

    private static final Logger LOG = Logger.getLogger(CameraController.class.getName());

    private final JavaPlugin plugin;
    private final CameraRoute route;
    private final List<Player> players;
    private final Runnable onComplete;

    private final Map<UUID, CinematicState> savedStates = new HashMap<>();
    private final Map<UUID, Integer> attachMisses = new HashMap<>();
    private final Set<UUID> teleportFallback = new HashSet<>();

    private BukkitTask task;
    private BukkitTask watchdog;
    private boolean    cancelled = false;
    private boolean    ending = false;
    private boolean    hidePlayers = true;

    // Playhead state
    private SplinePath path;
    private World world;
    private ItemDisplay camera;
    private int tick = 0;
    private int nextOverlay = 2;               // waypoint index whose overlay is sent next
    private final double[] sample = new double[5];

    public CameraController(JavaPlugin plugin, CameraRoute route,
                            Collection<Player> players, Runnable onComplete) {
        this.plugin     = plugin;
        this.route      = route;
        this.players    = new ArrayList<>(players);
        this.onComplete = onComplete;
    }

    /** Whether participants are hidden from each other while the camera plays (default true). */
    public CameraController hidePlayers(boolean hide) { this.hidePlayers = hide; return this; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Begins playback. Must be called from the main thread. */
    public void start() {
        List<CameraWaypoint> wps = route.getWaypoints();
        if (wps.size() < 2) {
            LOG.warning("[Cinematic] Route '" + route.getId() + "' has fewer than 2 waypoints — skipping.");
            finish();
            return;
        }
        Location first = wps.get(0).toLocation();
        world = first.getWorld();
        if (world == null) {
            LOG.warning("[Cinematic] Route '" + route.getId() + "' world is not loaded — skipping.");
            finish();
            return;
        }
        path = new SplinePath(wps);

        // Save state and freeze players
        players.removeIf(p -> !p.isOnline());
        for (Player p : players) {
            savedStates.put(p.getUniqueId(), CinematicState.capture(p));
            p.setGameMode(GameMode.SPECTATOR);
            p.sendTitle("", "", 0, 1, 0); // clear any existing title
            p.teleport(first);
        }
        if (hidePlayers) hideEachOther();

        if (plugin.getConfig().getBoolean("cinematic.smooth-camera", true)) spawnCamera(first);

        // Send initial overlay for first waypoint
        sendOverlay(wps.get(0));

        // Schedule repeating tick — every tick: the camera entity is interpolated client-side
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);

        // Safety watchdog: no matter what goes wrong in tick(), force-finish
        // (and restore players out of spectator) after the route's max runtime
        // plus a buffer. Guarantees a player can never be stuck in spectator.
        long maxTicks = route.totalTicks() + 100L;
        watchdog = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (task != null && !cancelled) {
                LOG.warning("[Cinematic] Route '" + route.getId() + "' watchdog fired — force-finishing.");
                finish();
            }
        }, maxTicks);
    }

    /**
     * Cancels the cinematic immediately and restores all players.
     * Safe to call at any time.
     */
    public void cancel() {
        cancelled = true;
        if (task != null)     { task.cancel();     task = null; }
        if (watchdog != null) { watchdog.cancel(); watchdog = null; }
        restoreAll();
        if (onComplete != null) onComplete.run();
    }

    /** Removes a player from the active cinematic (e.g. on disconnect). */
    public void removePlayer(UUID uuid) {
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) {
            releaseCamera(p);
            for (Player other : players) {
                if (other.equals(p) || !other.isOnline()) continue;
                other.showPlayer(plugin, p);
                p.showPlayer(plugin, other);
            }
        }
        players.removeIf(q -> q.getUniqueId().equals(uuid));
        CinematicState state = savedStates.remove(uuid);
        if (p != null && state != null) state.restore(p);
    }

    /**
     * Spectators can see each other (as ghostly heads), which spoils a flyover — everyone would watch the
     * others' heads swoop along. Hide all participants from one another for the duration instead.
     */
    private void hideEachOther() {
        for (Player viewer : players) {
            for (Player other : players) {
                if (viewer.equals(other)) continue;
                viewer.hidePlayer(plugin, other);
            }
        }
    }

    private void showEachOther() {
        for (Player viewer : players) {
            if (!viewer.isOnline()) continue;
            for (Player other : players) {
                if (viewer.equals(other) || !other.isOnline()) continue;
                viewer.showPlayer(plugin, other);
            }
        }
    }

    public boolean isActive()     { return task != null && !cancelled; }
    public String  getRouteId()   { return route.getId(); }
    public List<Player> getPlayers() { return Collections.unmodifiableList(players); }

    /** True while this controller wants {@code uuid} to stay on the camera (used to block "sneak to leave"). */
    public boolean holds(UUID uuid) {
        if (ending || cancelled) return false;
        for (Player p : players) if (p.getUniqueId().equals(uuid)) return true;
        return false;
    }

    // ── Camera entity ─────────────────────────────────────────────────────────

    private void spawnCamera(Location at) {
        try {
            camera = world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setGravity(false);
                d.setSilent(true);
                d.setTeleportDuration(TELEPORT_DURATION);
                d.setViewRange(0f);                      // nothing to render anyway
                d.setBillboard(Display.Billboard.FIXED);
                d.addScoreboardTag("kmc_cinematic_camera");
            });
        } catch (Throwable t) {
            LOG.warning("[Cinematic] Could not spawn camera entity (" + t + ") — using direct teleports.");
            camera = null;
        }
    }

    private void releaseCamera(Player p) {
        try {
            if (camera != null && p.isOnline() && p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
        } catch (Throwable ignored) { /* best effort */ }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private void tick() {
        try {
            tick0();
        } catch (Throwable t) {
            LOG.warning("[Cinematic] tick() failed for route '" + route.getId()
                    + "' — restoring players. " + t);
            finish();
        }
    }

    private void tick0() {
        if (cancelled || path == null) return;

        double duration = path.duration();
        tick++;
        if (tick > duration + 1) { finish(); return; }

        double u = SplinePath.warp(tick, duration, EASE_TICKS);
        path.sample(u, sample);
        Location pos = new Location(world, sample[0], sample[1], sample[2], (float) sample[3], (float) sample[4]);

        // Overlays: waypoint k (k >= 2) is announced when the camera sets off towards it.
        List<CameraWaypoint> wps = route.getWaypoints();
        while (nextOverlay < wps.size() && u >= path.knotTime(nextOverlay - 1)) {
            sendOverlay(wps.get(nextOverlay));
            nextOverlay++;
        }

        // Remove players who logged off
        players.removeIf(p -> !p.isOnline());

        boolean cameraAlive = camera != null && camera.isValid();
        if (cameraAlive) camera.teleport(pos);

        for (Player p : players) {
            if (!p.getGameMode().equals(GameMode.SPECTATOR)) {
                p.setGameMode(GameMode.SPECTATOR);
            }
            UUID id = p.getUniqueId();
            if (cameraAlive && !teleportFallback.contains(id)) {
                if (p.getSpectatorTarget() != camera) {
                    int misses = attachMisses.merge(id, 1, Integer::sum);
                    if (misses > MAX_ATTACH_MISSES) {
                        teleportFallback.add(id);
                        LOG.info("[Cinematic] " + p.getName() + " could not attach to the camera — direct teleports.");
                        p.teleport(pos);
                    } else {
                        p.setSpectatorTarget(camera);
                    }
                }
            } else {
                p.teleport(pos);
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void finish() {
        if (task != null)     { task.cancel();     task = null; }
        if (watchdog != null) { watchdog.cancel(); watchdog = null; }
        if (!cancelled) {
            restoreAll();
            if (onComplete != null) onComplete.run();
        }
    }

    private void restoreAll() {
        ending = true;
        // Let go of the camera first — restoring state teleports the player, which must not be fought by it.
        for (Player p : new ArrayList<>(players)) releaseCamera(p);
        if (camera != null) {
            try { camera.remove(); } catch (Throwable ignored) { /* already gone */ }
            camera = null;
        }
        showEachOther();
        for (Player p : new ArrayList<>(players)) {
            if (!p.isOnline()) continue;
            CinematicState state = savedStates.get(p.getUniqueId());
            if (state != null) {
                try { state.restore(p); } catch (Exception e) {
                    LOG.warning("[Cinematic] restore failed for " + p.getName() + ": " + e);
                }
            }
            // Hard safety: never leave anyone stuck in spectator from a cinematic.
            if (p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.ADVENTURE);
            }
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
