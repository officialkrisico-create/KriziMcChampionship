package nl.kmc.kmccore.presentation.camera;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A single point in a camera route.
 *
 * <p>The camera travels FROM the previous waypoint TO this one over
 * {@link #durationTicks} ticks using {@link #interpolation}.
 * Overlays (title, subtitle, action bar) are sent to players at the
 * moment the camera begins moving toward this waypoint.
 */
public final class CameraWaypoint {

    private final String world;
    private final double x, y, z;
    private final float  yaw, pitch;
    private final int    durationTicks;
    private final InterpolationType interpolation;
    private final String title;
    private final String subtitle;
    private final String actionBar;

    public CameraWaypoint(Location loc, int durationTicks,
                          InterpolationType interpolation,
                          String title, String subtitle, String actionBar) {
        this.world         = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        this.x             = loc.getX();
        this.y             = loc.getY();
        this.z             = loc.getZ();
        this.yaw           = loc.getYaw();
        this.pitch         = loc.getPitch();
        this.durationTicks = Math.max(1, durationTicks);
        this.interpolation = interpolation != null ? interpolation : InterpolationType.SMOOTH;
        this.title         = title    != null ? title    : "";
        this.subtitle      = subtitle != null ? subtitle : "";
        this.actionBar     = actionBar!= null ? actionBar: "";
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return new Location(w, x, y, z, yaw, pitch);
    }

    public String  getWorldName()     { return world; }
    public double  getX()             { return x; }
    public double  getY()             { return y; }
    public double  getZ()             { return z; }
    public float   getYaw()           { return yaw; }
    public float   getPitch()         { return pitch; }
    public int     getDurationTicks() { return durationTicks; }
    public InterpolationType getInterpolation() { return interpolation; }
    public String  getTitle()         { return title; }
    public String  getSubtitle()      { return subtitle; }
    public String  getActionBar()     { return actionBar; }

    // ── Serialisation ─────────────────────────────────────────────────────────

    public void save(ConfigurationSection sec) {
        sec.set("world",         world);
        sec.set("x",             x);
        sec.set("y",             y);
        sec.set("z",             z);
        sec.set("yaw",           (double) yaw);
        sec.set("pitch",         (double) pitch);
        sec.set("duration-ticks", durationTicks);
        sec.set("interpolation",  interpolation.name());
        sec.set("title",          title);
        sec.set("subtitle",       subtitle);
        sec.set("action-bar",     actionBar);
    }

    public static CameraWaypoint load(ConfigurationSection sec) {
        String world  = sec.getString("world", "world");
        double x      = sec.getDouble("x");
        double y      = sec.getDouble("y");
        double z      = sec.getDouble("z");
        float  yaw    = (float) sec.getDouble("yaw");
        float  pitch  = (float) sec.getDouble("pitch");
        int    dur    = sec.getInt("duration-ticks", 40);
        InterpolationType interp = InterpolationType.parse(sec.getString("interpolation"));
        String title  = sec.getString("title",      "");
        String sub    = sec.getString("subtitle",   "");
        String ab     = sec.getString("action-bar", "");

        World w = Bukkit.getWorld(world);
        Location loc = new Location(w, x, y, z, yaw, pitch);
        return new CameraWaypoint(loc, dur, interp, title, sub, ab);
    }

    /** Interpolates this position toward {@code to} by factor {@code t} (0–1). */
    public Location interpolateTo(CameraWaypoint to, float t) {
        float smoothT = to.getInterpolation().apply(t);

        double ix = x + (to.x - x) * smoothT;
        double iy = y + (to.y - y) * smoothT;
        double iz = z + (to.z - z) * smoothT;

        // Angle interpolation: handle yaw wrap-around
        float dy = to.yaw - yaw;
        if (dy > 180)  dy -= 360;
        if (dy < -180) dy += 360;
        float iyaw   = yaw   + dy * smoothT;
        float ipitch = pitch + (to.pitch - pitch) * smoothT;

        World w = Bukkit.getWorld(world);
        return new Location(w, ix, iy, iz, iyaw, ipitch);
    }
}
