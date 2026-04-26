package nl.kmc.spleef.managers;

import nl.kmc.spleef.SpleefPlugin;
import nl.kmc.spleef.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

/**
 * Places + clears the snow floor for a Spleef arena.
 *
 * <p>Per game: at game start, fills every block in the arena's layer
 * with the configured material (default SNOW_BLOCK). At game end,
 * any remaining floor blocks are cleared.
 *
 * <p>Also tracks which blocks ARE part of the floor so the BlockBreak
 * listener can distinguish "this is a floor block, allowed to break"
 * from "this is some random world block, not allowed".
 *
 * <p>Block placement is chunked (50 blocks/tick) for large arenas so
 * we don't lag the server on game start.
 */
public class FloorManager {

    private final SpleefPlugin plugin;

    /** Set of currently-placed floor block positions (encoded as long). */
    private final Set<Long> floorBlocks = new HashSet<>();

    private BukkitTask placementTask;
    private BukkitTask clearTask;

    public FloorManager(SpleefPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Placement — at game start
    // ----------------------------------------------------------------

    /**
     * Fills the arena's floor layer with the configured material.
     * Runs async chunked (50 blocks/tick) to avoid main-thread lag.
     *
     * @param onComplete callback when all blocks placed
     */
    public void placeFloorAsync(Arena arena, Runnable onComplete) {
        cancelTasks();
        floorBlocks.clear();

        if (arena.getLayers().isEmpty() || arena.getWorld() == null) {
            onComplete.run();
            return;
        }

        Material floorMat = parseMaterial(
                plugin.getConfig().getString("game.floor-material", "SNOW_BLOCK"),
                Material.SNOW_BLOCK);

        World world = arena.getWorld();
        Arena.Layer layer = arena.getTopLayer();
        if (layer == null) { onComplete.run(); return; }

        // Build the list of positions to place
        java.util.List<long[]> positions = new java.util.ArrayList<>();
        for (int x = layer.getMinX(); x <= layer.getMaxX(); x++) {
            for (int z = layer.getMinZ(); z <= layer.getMaxZ(); z++) {
                positions.add(new long[]{x, layer.getYLevel(), z});
            }
        }

        plugin.getLogger().info("Placing " + positions.size() + " floor blocks ("
                + floorMat + ")...");

        int batchSize = plugin.getConfig().getInt("game.placement-batch-size", 50);
        java.util.Iterator<long[]> iter = positions.iterator();

        placementTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int placed = 0;
            while (iter.hasNext() && placed < batchSize) {
                long[] pos = iter.next();
                Block b = world.getBlockAt((int) pos[0], (int) pos[1], (int) pos[2]);
                b.setType(floorMat, false);
                floorBlocks.add(encodePos((int) pos[0], (int) pos[1], (int) pos[2]));
                placed++;
            }
            if (!iter.hasNext()) {
                placementTask.cancel();
                placementTask = null;
                plugin.getLogger().info("Floor ready: " + floorBlocks.size() + " blocks placed.");
                onComplete.run();
            }
        }, 1L, 1L);
    }

    // ----------------------------------------------------------------
    // Clearing — at game end
    // ----------------------------------------------------------------

    /**
     * Clears any remaining floor blocks. Same chunked approach.
     */
    public void clearFloorAsync(Arena arena, Runnable onComplete) {
        cancelTasks();
        if (arena.getWorld() == null || floorBlocks.isEmpty()) {
            floorBlocks.clear();
            onComplete.run();
            return;
        }

        World world = arena.getWorld();
        java.util.List<Long> toClear = new java.util.ArrayList<>(floorBlocks);
        floorBlocks.clear();

        int batchSize = plugin.getConfig().getInt("game.placement-batch-size", 50);
        java.util.Iterator<Long> iter = toClear.iterator();

        clearTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int cleared = 0;
            while (iter.hasNext() && cleared < batchSize) {
                long encoded = iter.next();
                int[] coords = decodePos(encoded);
                Block b = world.getBlockAt(coords[0], coords[1], coords[2]);
                b.setType(Material.AIR, false);
                cleared++;
            }
            if (!iter.hasNext()) {
                clearTask.cancel();
                clearTask = null;
                onComplete.run();
            }
        }, 1L, 1L);
    }

    public void cancelTasks() {
        if (placementTask != null) { placementTask.cancel(); placementTask = null; }
        if (clearTask     != null) { clearTask.cancel();     clearTask = null; }
    }

    // ----------------------------------------------------------------
    // Floor block tracking
    // ----------------------------------------------------------------

    public boolean isFloorBlock(Block b) {
        return floorBlocks.contains(encodePos(b.getX(), b.getY(), b.getZ()));
    }

    public void unregisterBlock(Block b) {
        floorBlocks.remove(encodePos(b.getX(), b.getY(), b.getZ()));
    }

    public int getRemainingBlockCount() { return floorBlocks.size(); }

    // ----------------------------------------------------------------

    /** Pack (x, y, z) into a single long. y bits 0-15 (signed), z bits 16-39, x bits 40-63. */
    private static long encodePos(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40)
             | ((long) (z & 0xFFFFFF) << 16)
             | (y & 0xFFFF);
    }

    private static int[] decodePos(long encoded) {
        int x = (int) ((encoded >> 40) & 0xFFFFFF);
        if (x >= 0x800000) x -= 0x1000000;  // sign-extend 24-bit
        int z = (int) ((encoded >> 16) & 0xFFFFFF);
        if (z >= 0x800000) z -= 0x1000000;
        int y = (int) (encoded & 0xFFFF);
        if (y >= 0x8000) y -= 0x10000;      // sign-extend 16-bit
        return new int[]{x, y, z};
    }

    private static Material parseMaterial(String name, Material fallback) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (Exception e) { return fallback; }
    }
}
