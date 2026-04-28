package nl.kmc.adventure.models;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A 3D bounding box scoped to a specific checkpoint. When a racer enters
 * this box, they are teleported back to that checkpoint's respawn point.
 */
public class OutOfBoundsBox {

    private final String name;
    private final Location pos1;
    private final Location pos2;

    public OutOfBoundsBox(String name, Location pos1, Location pos2) {
        this.name = name;
        this.pos1 = pos1.clone();
        this.pos2 = pos2.clone();
    }

    public String getName()  { return name; }
    public Location getPos1() { return pos1.clone(); }
    public Location getPos2() { return pos2.clone(); }

    /** True if {@code loc} is anywhere inside this box. */
    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        World w = pos1.getWorld();
        if (w == null || !w.equals(loc.getWorld())) return false;

        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    public String describe() {
        return "[" + (int) pos1.getX() + "," + (int) pos1.getY() + "," + (int) pos1.getZ()
             + " → " + (int) pos2.getX() + "," + (int) pos2.getY() + "," + (int) pos2.getZ() + "]";
    }
}
