package nl.kmc.speedbuild.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/** Region maths: per-player slot layout and fast cuboid clearing. */
public final class RegionUtil {

    private RegionUtil() {}

    /**
     * Minimum corner of a player's build slot. Slots tile along +X with a gap
     * so no two players ever share blocks (deterministic, no manual setup).
     */
    public static Location slotMin(Location anchor, int slotIndex, int slotWidthX, int gap) {
        return anchor.clone().add((double) slotIndex * (slotWidthX + gap), 0, 0);
    }

    /** Blueprint reference sits just past the build area along +Z. */
    public static Location blueprintMin(Location buildMin, int depthZ, int gap) {
        return buildMin.clone().add(0, 0, depthZ + gap);
    }

    /** Clears a cuboid (sets to air). {@code min} is the lowest corner. */
    public static void clear(Location min, int dx, int dy, int dz) {
        World w = min.getWorld();
        int bx = min.getBlockX(), by = min.getBlockY(), bz = min.getBlockZ();
        for (int x = 0; x < dx; x++)
            for (int y = 0; y < dy; y++)
                for (int z = 0; z < dz; z++) {
                    var b = w.getBlockAt(bx + x, by + y, bz + z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
    }

    /** A safe standing spot just outside the build area, facing it. */
    public static Location standOn(Location buildMin, int dx, int dz) {
        Location l = buildMin.clone().add(dx / 2.0, 0, -1.5);
        l.setYaw(0f);
        return l;
    }

    /** True if {@code loc} is inside the cuboid {@code [min, min+dims)}. */
    public static boolean contains(Location min, int dx, int dy, int dz, Location loc) {
        if (min.getWorld() == null || loc.getWorld() == null
                || !min.getWorld().equals(loc.getWorld())) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= min.getBlockX() && x < min.getBlockX() + dx
            && y >= min.getBlockY() && y < min.getBlockY() + dy
            && z >= min.getBlockZ() && z < min.getBlockZ() + dz;
    }
}
