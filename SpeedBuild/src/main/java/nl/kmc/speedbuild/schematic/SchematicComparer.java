package nl.kmc.speedbuild.schematic;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Deterministic block-by-block comparison of a built world region against a
 * schematic clipboard. Comparison is anchored at the build region's minimum
 * corner, so the same build always yields the same score.
 *
 * <p>Block matching is by {@link Material} (type), ignoring orientation/state
 * so it stays forgiving and deterministic.
 */
public final class SchematicComparer {

    private SchematicComparer() {}

    /**
     * @param correct        schematic blocks reproduced correctly
     * @param totalSchematic total non-air blocks in the schematic
     * @param missing        schematic blocks missing or wrong
     * @param extra          blocks placed where the schematic is air
     */
    public record Diff(int correct, int totalSchematic, int missing, int extra) {
        /** Fraction 0..1 of schematic blocks reproduced. */
        public double accuracy() { return totalSchematic == 0 ? 0 : (double) correct / totalSchematic; }
        public int wrongBlocks() { return missing + extra; }
    }

    /** Compares the world region anchored at {@code buildMin} to the schematic. */
    public static Diff compare(Clipboard clipboard, Location buildMin) {
        Region region = clipboard.getRegion();
        BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
        int dx = max.x() - min.x() + 1;
        int dy = max.y() - min.y() + 1;
        int dz = max.z() - min.z() + 1;

        int correct = 0, total = 0, extra = 0;
        var world = buildMin.getWorld();
        int bx = buildMin.getBlockX(), by = buildMin.getBlockY(), bz = buildMin.getBlockZ();

        for (int x = 0; x < dx; x++) {
            for (int y = 0; y < dy; y++) {
                for (int z = 0; z < dz; z++) {
                    Material schem = WorldEditAdapter.materialAt(clipboard, min.add(x, y, z));
                    Material built = world.getBlockAt(bx + x, by + y, bz + z).getType();
                    boolean schemAir = schem == null || schem.isAir();
                    boolean builtAir = built.isAir();
                    if (schemAir) {
                        if (!builtAir) extra++;
                    } else {
                        total++;
                        if (built == schem) correct++;
                    }
                }
            }
        }
        int missing = total - correct;
        return new Diff(correct, total, missing, extra);
    }
}
