package nl.kmc.bridge.managers;

import nl.kmc.bridge.TheBridgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Tracks blocks that players place during a Bridge match.
 *
 * <p>At game end, all tracked blocks are removed so the arena returns
 * to its original state for the next game. Cleared async chunked
 * (50 blocks/tick) to avoid main-thread lag.
 *
 * <p>We only track WOOL placements (the kit material). If a player
 * places anything else, we'd cancel that earlier in BlockListener.
 */
public class BlockTracker {

    private final TheBridgePlugin plugin;
    private final Set<Long> placedBlocks = new HashSet<>();
    private BukkitTask clearTask;

    public BlockTracker(TheBridgePlugin plugin) { this.plugin = plugin; }

    public void recordPlacement(Block b) {
        placedBlocks.add(encodePos(b.getX(), b.getY(), b.getZ()));
    }

    /** Returns true if this block was placed by a player during the match. */
    public boolean wasPlaced(Block b) {
        return placedBlocks.contains(encodePos(b.getX(), b.getY(), b.getZ()));
    }

    public void unrecord(Block b) {
        placedBlocks.remove(encodePos(b.getX(), b.getY(), b.getZ()));
    }

    public int getCount() { return placedBlocks.size(); }

    /**
     * Async-clear all tracked placements. Calls onComplete on the
     * main thread when done.
     */
    public void clearAllAsync(World world, Runnable onComplete) {
        if (clearTask != null) { clearTask.cancel(); clearTask = null; }
        if (placedBlocks.isEmpty()) {
            onComplete.run();
            return;
        }

        java.util.List<Long> snapshot = new java.util.ArrayList<>(placedBlocks);
        placedBlocks.clear();

        int batchSize = plugin.getConfig().getInt("game.cleanup-batch-size", 50);
        Iterator<Long> iter = snapshot.iterator();

        clearTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int cleared = 0;
            while (iter.hasNext() && cleared < batchSize) {
                long encoded = iter.next();
                int[] coords = decodePos(encoded);
                Block b = world.getBlockAt(coords[0], coords[1], coords[2]);
                // Only clear if it's still wool (the player might have broken it)
                if (b.getType().name().endsWith("_WOOL")) {
                    b.setType(Material.AIR, false);
                }
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
        if (clearTask != null) { clearTask.cancel(); clearTask = null; }
    }

    /** Pack (x, y, z) into a long. y bits 0-15 signed, z 16-39, x 40-63. */
    private static long encodePos(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40)
             | ((long) (z & 0xFFFFFF) << 16)
             | (y & 0xFFFF);
    }

    private static int[] decodePos(long encoded) {
        int x = (int) ((encoded >> 40) & 0xFFFFFF);
        if (x >= 0x800000) x -= 0x1000000;
        int z = (int) ((encoded >> 16) & 0xFFFFFF);
        if (z >= 0x800000) z -= 0x1000000;
        int y = (int) (encoded & 0xFFFF);
        if (y >= 0x8000) y -= 0x10000;
        return new int[]{x, y, z};
    }
}
