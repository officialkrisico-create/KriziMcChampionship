package nl.kmc.game.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Small block-building helpers for the generated test arenas. Everything is placed without
 * physics updates and at a fixed high altitude, so a test arena never collides with terrain
 * and works the same in a void, flat or normal world.
 */
public final class TestArenaKit {

    private TestArenaKit() {}

    /** Build level: high enough to be clear of any terrain, low enough to leave headroom. */
    public static int baseY(World world) {
        return Math.min(world.getMaxHeight() - 80, 200);
    }

    /** A location at the anchor X/Z of an origin, on the build level. */
    public static Location anchor(Location origin) {
        return new Location(origin.getWorld(), origin.getBlockX(), baseY(origin.getWorld()), origin.getBlockZ());
    }

    /** Fills the inclusive cuboid between two corners. */
    public static void fill(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    w.getBlockAt(x, y, z).setType(m, false);
    }

    /** A flat one-block-thick platform centred on (cx, cz). */
    public static void platform(World w, int cx, int y, int cz, int halfX, int halfZ, Material m) {
        fill(w, cx - halfX, y, cz - halfZ, cx + halfX, y, cz + halfZ, m);
    }

    /** Hollow walls (no floor/ceiling) around a rectangle, {@code height} blocks tall, starting at {@code y}. */
    public static void walls(World w, int cx, int y, int cz, int halfX, int halfZ, int height, Material m) {
        fill(w, cx - halfX, y, cz - halfZ, cx + halfX, y + height - 1, cz - halfZ, m);
        fill(w, cx - halfX, y, cz + halfZ, cx + halfX, y + height - 1, cz + halfZ, m);
        fill(w, cx - halfX, y, cz - halfZ, cx - halfX, y + height - 1, cz + halfZ, m);
        fill(w, cx + halfX, y, cz - halfZ, cx + halfX, y + height - 1, cz + halfZ, m);
    }

    /** A circular disc (filled) of the given radius. */
    public static void disc(World w, int cx, int y, int cz, int radius, Material m) {
        for (int x = -radius; x <= radius; x++)
            for (int z = -radius; z <= radius; z++)
                if (x * x + z * z <= radius * radius) w.getBlockAt(cx + x, y, cz + z).setType(m, false);
    }

    /** {@code count} standing positions evenly spread on a circle, each facing the centre. */
    public static java.util.List<Location> ring(World w, int cx, int feetY, int cz, int radius, int count) {
        java.util.List<Location> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            double ang = Math.PI * 2 * i / count;
            int px = cx + (int) Math.round(radius * Math.cos(ang));
            int pz = cz + (int) Math.round(radius * Math.sin(ang));
            float yaw = (float) Math.toDegrees(Math.atan2(-(cx - px), cz - pz));
            out.add(stand(w, px, feetY, pz, yaw));
        }
        return out;
    }

    /** Standing position centred on a block (feet at {@code y}), facing {@code yaw}. */
    public static Location stand(World w, double x, double y, double z, float yaw) {
        return new Location(w, x + 0.5, y, z + 0.5, yaw, 0f);
    }
}
