package nl.kmc.formula.models;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * An axis-aligned box a racer has to drive through (start/finish line or a checkpoint). Corners are block
 * coordinates; the box includes the full corner blocks.
 */
public final class Gate {

    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    public Gate(Location a, Location b) {
        this(a.getWorld() != null ? a.getWorld().getName() : "world",
                a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    public Gate(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    /** Boat coordinates are doubles; allow a little vertical slack (boats ride on the surface, 1 block up). */
    public boolean contains(Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        double x = l.getX(), y = l.getY(), z = l.getZ();
        return x >= minX && x < maxX + 1
            && y >= minY - 1.5 && y < maxY + 2.5
            && z >= minZ && z < maxZ + 1;
    }

    public boolean contains(World w, double x, double y, double z) {
        return contains(new Location(w, x, y, z));
    }

    public Location center(World w) {
        return new Location(w, (minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0);
    }

    public String getWorld() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public String describe() {
        return "(" + minX + "," + minY + "," + minZ + ") → (" + maxX + "," + maxY + "," + maxZ + ")";
    }
}
