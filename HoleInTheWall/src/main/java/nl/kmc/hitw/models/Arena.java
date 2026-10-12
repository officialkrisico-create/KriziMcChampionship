package nl.kmc.hitw.models;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The round platform: a disc of blocks over the void. Centre and radius are all that is needed — the
 * walls are entities, so nothing else about the arena is built or reset between rounds.
 *
 * <p>{@code (cx, cy, cz)} is the centre BLOCK of the platform surface; players stand with their feet at
 * {@code cy + 1}.
 */
public final class Arena {

    private World world;
    private int cx, cy, cz;
    private int radius = 18;
    private Location spectator;
    private Material platformMaterial = Material.SMOOTH_QUARTZ;
    private Material borderMaterial   = Material.RED_CONCRETE;
    private boolean centerSet;

    public World getWorld()            { return world; }
    public void  setWorld(World w)     { this.world = w; }
    public int   getRadius()           { return radius; }
    public void  setRadius(int r)      { this.radius = Math.max(6, r); }
    public void  setCenter(int x, int y, int z) { this.cx = x; this.cy = y; this.cz = z; this.centerSet = true; }
    public boolean isCenterSet()       { return centerSet; }
    public int   getCx() { return cx; }
    public int   getCy() { return cy; }
    public int   getCz() { return cz; }
    public void  setSpectator(Location l)   { this.spectator = l == null ? null : l.clone(); }
    public Location getSpectatorRaw()       { return spectator == null ? null : spectator.clone(); }
    public void  setPlatformMaterial(Material m) { if (m != null) this.platformMaterial = m; }
    public void  setBorderMaterial(Material m)   { if (m != null) this.borderMaterial = m; }

    public boolean isReady() { return world != null && centerSet; }

    /** Centre of the platform in world coordinates (middle of the block column). */
    public double centerX() { return cx + 0.5; }
    public double centerZ() { return cz + 0.5; }

    /** Y a player's feet are at when standing on the platform. */
    public double floorY()  { return cy + 1; }

    public Location centerLocation() { return new Location(world, centerX(), floorY(), centerZ()); }

    /** Where eliminated players watch from: the configured spot, or well above the middle. */
    public Location spectatorSpawn() {
        if (spectator != null) return spectator.clone();
        return new Location(world, centerX(), floorY() + 14, centerZ(), 0f, 45f);
    }

    /**
     * {@code count} start positions spread evenly over the platform (sunflower spiral), each facing the
     * middle, so nobody starts on top of someone else or on the rim.
     */
    public List<Location> spawnPositions(int count, Random rnd) {
        List<Location> out = new ArrayList<>();
        double golden = Math.PI * (3 - Math.sqrt(5));
        double offset = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double r = 0.7 * radius * Math.sqrt((i + 0.5) / Math.max(1, count));
            double a = offset + i * golden;
            double x = centerX() + Math.cos(a) * r, z = centerZ() + Math.sin(a) * r;
            float yaw = (float) Math.toDegrees(Math.atan2(-(centerX() - x), centerZ() - z));
            out.add(new Location(world, x, floorY(), z, yaw, 0f));
        }
        return out;
    }

    /** Distance from the platform centre, in the XZ plane. */
    public double distanceFromCenter(double x, double z) {
        return Math.hypot(x - centerX(), z - centerZ());
    }

    /** Builds (or rebuilds) the disc: platform material with a clearly visible border ring. */
    public void buildPlatform() {
        if (!isReady()) return;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius + 0.3) continue;
                boolean border = d >= radius - 1.0;
                world.getBlockAt(cx + dx, cy, cz + dz).setType(border ? borderMaterial : platformMaterial, false);
            }
        }
    }
}
