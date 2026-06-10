package nl.kmc.blockparty.managers;

import nl.kmc.blockparty.models.Colors;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

/**
 * Generates and clears the Block Party floor. The floor is a single layer of
 * coloured concrete inside the arena rectangle, painted with a Voronoi-style
 * cluster layout so colours form contiguous blobs rather than random noise.
 *
 * <p>Cluster size shrinks as the match progresses (fewer seeds = bigger blobs,
 * more seeds = smaller scattered islands), driving the difficulty curve.
 */
public final class FloorGenerator {

    private final ArenaManager arena;
    private final Random random = new Random();

    public FloorGenerator(ArenaManager arena) {
        this.arena = arena;
    }

    /** Result of a generation pass: the colours used and how many blocks of each. */
    public record Result(List<Material> palette, Map<Material, Integer> counts) {}

    /**
     * Repaints the entire floor.
     *
     * @param colourCount  how many distinct colours to use
     * @param clusterSize  average blocks per colour blob (smaller = harder)
     */
    public Result generate(int colourCount, int clusterSize) {
        World world = arena.getWorld();
        int minX = arena.minX(), maxX = arena.maxX();
        int minZ = arena.minZ(), maxZ = arena.maxZ();
        int y    = arena.floorY();
        int area = arena.area();

        // Pick the palette.
        List<Material> palette = new ArrayList<>(Colors.ALL);
        Collections.shuffle(palette, random);
        palette = new ArrayList<>(palette.subList(0, Math.max(2, Math.min(colourCount, palette.size()))));

        // Seeds: enough that the average blob ≈ clusterSize, but at least one per colour.
        int seedCount = Math.max(palette.size(), Math.min(area / 2, Math.max(1, area / Math.max(1, clusterSize))));
        int[]      sx = new int[seedCount];
        int[]      sz = new int[seedCount];
        Material[] sc = new Material[seedCount];
        for (int i = 0; i < seedCount; i++) {
            sx[i] = minX + random.nextInt(maxX - minX + 1);
            sz[i] = minZ + random.nextInt(maxZ - minZ + 1);
            // First N seeds guarantee every palette colour appears at least once.
            sc[i] = i < palette.size() ? palette.get(i) : palette.get(random.nextInt(palette.size()));
        }

        Map<Material, Integer> counts = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Material colour = nearestSeedColour(x, z, sx, sz, sc);
                Block b = world.getBlockAt(x, y, z);
                b.setType(colour, false);
                // Clear a little headroom so nothing left over from last round blocks movement.
                for (int dy = 1; dy <= 2; dy++) {
                    Block above = world.getBlockAt(x, y + dy, z);
                    if (above.getType() != Material.AIR) above.setType(Material.AIR, false);
                }
                counts.merge(colour, 1, Integer::sum);
            }
        }
        return new Result(palette, counts);
    }

    private Material nearestSeedColour(int x, int z, int[] sx, int[] sz, Material[] sc) {
        long best = Long.MAX_VALUE;
        Material colour = sc[0];
        for (int i = 0; i < sx.length; i++) {
            long dx = x - sx[i], dz = z - sz[i];
            long d = dx * dx + dz * dz;
            if (d < best) { best = d; colour = sc[i]; }
        }
        return colour;
    }

    /** Removes every floor block whose colour is not in {@code keep} (sets it to air). */
    public void removeAllExcept(Set<Material> keep) {
        World world = arena.getWorld();
        int y = arena.floorY();
        for (int x = arena.minX(); x <= arena.maxX(); x++) {
            for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                Block b = world.getBlockAt(x, y, z);
                if (Colors.isConcrete(b.getType()) && !keep.contains(b.getType()))
                    b.setType(Material.AIR, false);
            }
        }
    }

    /** Wipes the whole floor to air (used on game end / cleanup). */
    public void clear() {
        World world = arena.getWorld();
        int y = arena.floorY();
        for (int x = arena.minX(); x <= arena.maxX(); x++)
            for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                Block b = world.getBlockAt(x, y, z);
                if (Colors.isConcrete(b.getType())) b.setType(Material.AIR, false);
            }
    }

    /** A random standing location centred on a floor block (used by RANDOM_TP). */
    public org.bukkit.Location randomFloorLocation() {
        int x = arena.minX() + random.nextInt(arena.maxX() - arena.minX() + 1);
        int z = arena.minZ() + random.nextInt(arena.maxZ() - arena.minZ() + 1);
        return new org.bukkit.Location(arena.getWorld(), x + 0.5, arena.floorY() + 1, z + 0.5);
    }
}
