package nl.kmc.kmccore.presentation;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.camera.CameraController;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central manager for the cinematic presentation system.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Loads and saves camera routes from {@code cameras.yml}.
 *   <li>Provides the recording API used by {@code /kmccamera}.
 *   <li>Manages active {@link CameraController} instances.
 *   <li>Exposes a high-level {@link #playRoute} method used by the
 *       {@code AutomationManager} ceremony/cinematic hooks.
 * </ul>
 *
 * <h2>Route naming convention</h2>
 * <pre>
 *   opening               — tournament opening ceremony
 *   team-showcase         — team introduction flyover
 *   tournament-overview   — game lineup reveal
 *   game-intro-{gameId}   — per-game introduction
 *   arena-{gameId}        — arena flyover before a game
 *   winner-{gameId}       — post-game winner ceremony
 *   closing               — tournament finale
 * </pre>
 */
public final class CinematicManager {

    private static final Logger LOG = Logger.getLogger(CinematicManager.class.getName());

    private final KMCCore plugin;
    private final File    camerasFile;
    private FileConfiguration camerasConfig;

    /** All loaded routes keyed by ID. */
    private final Map<String, CameraRoute> routes = new LinkedHashMap<>();

    /** Active controllers keyed by route ID. Only one instance per route at a time. */
    private final Map<String, CameraController> active = new HashMap<>();

    /** Players currently in any cinematic — maps UUID → controller. */
    private final Map<UUID, CameraController> playerInCinematic = new HashMap<>();

    // ── WIP recording state (one route being built at a time per player) ──────
    private final Map<UUID, CameraRoute> pendingRoutes = new HashMap<>();

    public CinematicManager(KMCCore plugin) {
        this.plugin      = plugin;
        this.camerasFile = new File(plugin.getDataFolder(), "cameras.yml");
        if (!camerasFile.exists()) plugin.saveResource("cameras.yml", false);
        reload();
    }

    // ── Route loading / saving ────────────────────────────────────────────────

    /** Reloads all routes from {@code cameras.yml}. */
    public void reload() {
        camerasConfig = YamlConfiguration.loadConfiguration(camerasFile);
        routes.clear();
        ConfigurationSection sec = camerasConfig.getConfigurationSection("routes");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection routeSec = sec.getConfigurationSection(id);
                if (routeSec != null) {
                    CameraRoute route = CameraRoute.load(id, routeSec);
                    routes.put(id, route);
                }
            }
        }
        LOG.info("[Cinematic] Loaded " + routes.size() + " camera route(s).");
    }

    /** Persists all routes to {@code cameras.yml}. */
    public void save() {
        camerasConfig.set("routes", null); // clear
        ConfigurationSection routesSec = camerasConfig.createSection("routes");
        for (CameraRoute route : routes.values()) {
            route.save(routesSec);
        }
        try { camerasConfig.save(camerasFile); }
        catch (Exception e) { LOG.log(Level.SEVERE, "Failed to save cameras.yml", e); }
    }

    // ── Route management ──────────────────────────────────────────────────────

    public Optional<CameraRoute> getRoute(String id) {
        return Optional.ofNullable(routes.get(id));
    }

    public Collection<CameraRoute> getAllRoutes() {
        return Collections.unmodifiableCollection(routes.values());
    }

    public boolean routeExists(String id) { return routes.containsKey(id); }

    public void deleteRoute(String id) {
        routes.remove(id);
        save();
    }

    // ── WIP recording API (used by /kmccamera) ────────────────────────────────

    /** Starts recording a new route for the player. Any previous WIP is discarded. */
    public void startRecording(UUID player, String routeId, String description) {
        pendingRoutes.put(player, new CameraRoute(routeId, description));
    }

    /** Returns true if the player has an active recording session. */
    public boolean isRecording(UUID player) { return pendingRoutes.containsKey(player); }

    /** Returns the route being recorded, or empty. */
    public Optional<CameraRoute> getPendingRoute(UUID player) {
        return Optional.ofNullable(pendingRoutes.get(player));
    }

    /**
     * Adds a waypoint to the player's pending route.
     * @return false if the player has no active recording.
     */
    public boolean addWaypoint(UUID player, CameraWaypoint waypoint) {
        CameraRoute route = pendingRoutes.get(player);
        if (route == null) return false;
        route.addWaypoint(waypoint);
        return true;
    }

    /**
     * Removes the last waypoint from the player's pending route.
     * @return false if nothing to remove.
     */
    public boolean removeLastWaypoint(UUID player) {
        CameraRoute route = pendingRoutes.get(player);
        if (route == null || route.isEmpty()) return false;
        return route.removeWaypoint(route.size() - 1);
    }

    /**
     * Saves the pending route and clears the recording session.
     * @return the saved route, or empty if no recording active.
     */
    public Optional<CameraRoute> saveRecording(UUID player) {
        CameraRoute route = pendingRoutes.remove(player);
        if (route == null) return Optional.empty();
        routes.put(route.getId(), route);
        save();
        return Optional.of(route);
    }

    /** Discards the pending route without saving. */
    public void discardRecording(UUID player) { pendingRoutes.remove(player); }

    // ── Playback API ──────────────────────────────────────────────────────────

    /**
     * Plays a route for a collection of players.
     *
     * <p>If the route doesn't exist, {@code onComplete} is called immediately
     * so the calling code can continue without modification.
     *
     * @param routeId    route to play
     * @param players    who to show the cinematic to
     * @param onComplete called on the main thread when the cinematic ends
     * @return true if the route was found and playback started
     */
    public boolean playRoute(String routeId, Collection<Player> players, Runnable onComplete) {
        CameraRoute route = routes.get(routeId);
        if (route == null || route.isEmpty()) {
            LOG.fine("[Cinematic] Route '" + routeId + "' not found or empty — skipping.");
            if (onComplete != null) onComplete.run();
            return false;
        }
        return playRouteInstance(routeId, route, players, onComplete);
    }

    /**
     * Plays a {@link CameraRoute} object directly (not necessarily saved), tracked
     * under {@code trackingKey} so {@link #stopAll()}, {@link #stopRoute},
     * {@link #onPlayerQuit} and the watchdog all apply — fixing the previous
     * "stuck in spectator" bug where previews ran on an untracked controller.
     */
    public boolean playRouteInstance(String trackingKey, CameraRoute route,
                                     Collection<Player> players, Runnable onComplete) {
        if (route == null || route.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return false;
        }

        stopRoute(trackingKey); // stop any existing playback under this key

        List<Player> online = players.stream().filter(Player::isOnline).toList();
        if (online.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return false;
        }

        CameraController controller = new CameraController(plugin, route, online, () -> {
            active.remove(trackingKey);
            online.forEach(p -> playerInCinematic.remove(p.getUniqueId()));
            if (onComplete != null) onComplete.run();
        });

        active.put(trackingKey, controller);
        online.forEach(p -> playerInCinematic.put(p.getUniqueId(), controller));

        controller.start();
        LOG.info("[Cinematic] Playing route '" + trackingKey + "' for " + online.size() + " player(s).");
        return true;
    }

    /** Previews a route object for a single viewer — fully tracked and watchdog-guarded. */
    public boolean previewRoute(CameraRoute route, Player viewer, Runnable onComplete) {
        return playRouteInstance("__preview__:" + viewer.getUniqueId(), route, List.of(viewer), onComplete);
    }

    /** Convenience overload: plays for all online players. */
    public boolean playRouteForAll(String routeId, Runnable onComplete) {
        return playRoute(routeId, new ArrayList<>(plugin.getServer().getOnlinePlayers()), onComplete);
    }

    /** Stops playback of a specific route and restores affected players. */
    public void stopRoute(String routeId) {
        CameraController c = active.remove(routeId);
        if (c != null) c.cancel();
    }

    /** Stops ALL active cinematics. */
    public void stopAll() {
        new ArrayList<>(active.values()).forEach(CameraController::cancel);
        active.clear();
        playerInCinematic.clear();
    }

    /** @return true if the player is currently in any cinematic. */
    public boolean isInCinematic(UUID uuid) {
        return playerInCinematic.containsKey(uuid);
    }

    /** Removes a player from their active cinematic (e.g. on disconnect). */
    public void onPlayerQuit(UUID uuid) {
        CameraController c = playerInCinematic.remove(uuid);
        if (c != null) c.removePlayer(uuid);
    }

    /**
     * Returns the route ID associated with the given phase name.
     * This is just the identity mapping — callers pass route IDs directly.
     * Helper kept for documentation clarity.
     */
    public static String routeForPhase(String phase) { return phase; }
}
