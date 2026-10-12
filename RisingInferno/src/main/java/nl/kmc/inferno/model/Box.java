package nl.kmc.inferno.model;

/** An axis-aligned block box (corners inclusive) used for the lava area and the finish. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** True if the point is inside (blocks span [min, max+1) in each axis). */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    /** Same as {@link #contains} but ignoring height (and grown by {@code margin} blocks sideways). */
    public boolean containsXZ(double x, double z, double margin) {
        return x >= minX - margin && x < maxX + 1 + margin && z >= minZ - margin && z < maxZ + 1 + margin;
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }
}
