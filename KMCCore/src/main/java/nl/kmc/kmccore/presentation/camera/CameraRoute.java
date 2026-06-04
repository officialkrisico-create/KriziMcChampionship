package nl.kmc.kmccore.presentation.camera;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named sequence of {@link CameraWaypoint}s that form a cinematic route.
 *
 * <p>Routes are stored in {@code cameras.yml} and loaded by {@link nl.kmc.kmccore.presentation.CinematicManager}.
 * Each route has a human-readable description used in the admin UI.
 *
 * <h3>Built-in route name conventions</h3>
 * <table>
 *   <tr><th>Route ID</th><th>Used for</th></tr>
 *   <tr><td>{@code opening}</td><td>Tournament opening ceremony</td></tr>
 *   <tr><td>{@code team-showcase}</td><td>Team intro</td></tr>
 *   <tr><td>{@code game-intro-{gameId}}</td><td>Per-game pre-game intro</td></tr>
 *   <tr><td>{@code arena-{gameId}}</td><td>Arena flyover before game starts</td></tr>
 *   <tr><td>{@code winner-{gameId}}</td><td>Post-game winner ceremony</td></tr>
 *   <tr><td>{@code closing}</td><td>Tournament finale</td></tr>
 * </table>
 */
public final class CameraRoute {

    private final String              id;
    private final String              description;
    private final List<CameraWaypoint> waypoints;

    public CameraRoute(String id, String description) {
        this.id          = id;
        this.description = description;
        this.waypoints   = new ArrayList<>();
    }

    private CameraRoute(String id, String description, List<CameraWaypoint> waypoints) {
        this.id          = id;
        this.description = description;
        this.waypoints   = new ArrayList<>(waypoints);
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    public void addWaypoint(CameraWaypoint wp) { waypoints.add(wp); }

    public boolean removeWaypoint(int index) {
        if (index < 0 || index >= waypoints.size()) return false;
        waypoints.remove(index);
        return true;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public String              getId()          { return id; }
    public String              getDescription() { return description; }
    public List<CameraWaypoint> getWaypoints()  { return Collections.unmodifiableList(waypoints); }
    public boolean             isEmpty()        { return waypoints.isEmpty(); }
    public int                 size()           { return waypoints.size(); }

    /** Total duration of the route in ticks (sum of all waypoint durations). */
    public int totalTicks() {
        return waypoints.stream().mapToInt(CameraWaypoint::getDurationTicks).sum();
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    public void save(ConfigurationSection parent) {
        ConfigurationSection sec = parent.createSection(id);
        sec.set("description", description);
        ConfigurationSection wps = sec.createSection("waypoints");
        for (int i = 0; i < waypoints.size(); i++) {
            waypoints.get(i).save(wps.createSection(String.valueOf(i)));
        }
    }

    public static CameraRoute load(String id, ConfigurationSection sec) {
        String desc = sec.getString("description", id);
        List<CameraWaypoint> wps = new ArrayList<>();
        ConfigurationSection wpsec = sec.getConfigurationSection("waypoints");
        if (wpsec != null) {
            // Keys are "0", "1", "2" ... sort numerically
            wpsec.getKeys(false).stream()
                    .sorted((a, b) -> {
                        try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                        catch (NumberFormatException e) { return a.compareTo(b); }
                    })
                    .forEach(key -> {
                        ConfigurationSection wp = wpsec.getConfigurationSection(key);
                        if (wp != null) wps.add(CameraWaypoint.load(wp));
                    });
        }
        return new CameraRoute(id, desc, wps);
    }
}
