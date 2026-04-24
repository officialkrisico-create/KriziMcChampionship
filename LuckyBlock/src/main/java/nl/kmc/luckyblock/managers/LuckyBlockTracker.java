package nl.kmc.luckyblock.managers;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Scans the freshly pasted arena schematic for {@link Material#YELLOW_CONCRETE}
 * blocks and registers them as active lucky blocks.
 *
 * <p>This approach means you don't have to manually register each lucky block
 * location — just place yellow concrete wherever you want them in your
 * schematic, and the tracker finds them all automatically.
 *
 * <p>When a lucky block is broken during a game it's removed from the
 * active set. The arena reset (re-paste) restores them all back to yellow concrete.
 */
public class LuckyBlockTracker {

    public static final Material LUCKY_BLOCK_MATERIAL = Material.YELLOW_CONCRETE;

    private final LuckyBlockPlugin plugin;

    /** Set of active (unbroken) lucky block locations. */
    private final Set<Location> activeBlocks = new HashSet<>();

    public LuckyBlockTracker(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------
    // Scanning
    // ----------------------------------------------------------------

    /**
     * Scans the world around the given origin for yellow concrete blocks
     * and registers them as lucky blocks.
     *
     * @param origin   the schematic paste origin
     * @param radius   max scan radius in blocks (default 200 is plenty for most arenas)
     */
    public void scanForLuckyBlocks(Location origin, int radius) {
        activeBlocks.clear();
        if (origin == null) return;

        World world  = origin.getWorld();
        int   ox     = origin.getBlockX();
        int   oy     = origin.getBlockY();
        int   oz     = origin.getBlockZ();

        plugin.getLogger().info("Scanning for lucky blocks around "
                + ox + "," + oy + "," + oz + " (radius " + radius + ")...");

        int minX = ox - radius, maxX = ox + radius;
        int minY = Math.max(world.getMinHeight(), oy - radius / 2);
        int maxY = Math.min(world.getMaxHeight(), oy + radius / 2);
        int minZ = oz - radius, maxZ = oz + radius;

        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Ensure chunk is loaded — avoids generating new terrain
                Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
                if (!chunk.isLoaded()) chunk.load(false);

                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == LUCKY_BLOCK_MATERIAL) {
                        activeBlocks.add(b.getLocation());
                        count++;
                    }
                }
            }
        }

        plugin.getLogger().info("Found " + count + " lucky blocks.");
    }

    /** Scans with the default radius from config. */
    public void scanForLuckyBlocks(Location origin) {
        int radius = plugin.getConfig().getInt("game.scan-radius", 200);
        scanForLuckyBlocks(origin, radius);
    }

    // ----------------------------------------------------------------
    // Block operations
    // ----------------------------------------------------------------

    /**
     * Checks if a location is a tracked lucky block.
     */
    public boolean isLuckyBlock(Location loc) {
        return activeBlocks.contains(loc.getBlock().getLocation())
                && loc.getBlock().getType() == LUCKY_BLOCK_MATERIAL;
    }

    /**
     * Marks a block as broken — removes it from active set.
     */
    public void onBlockBroken(Location loc) {
        activeBlocks.remove(loc.getBlock().getLocation());
    }

    /**
     * Clears the active set (called on game end before arena reset).
     */
    public void clear() {
        activeBlocks.clear();
    }

    public int getActiveCount() { return activeBlocks.size(); }
    public Set<Location> getActiveBlocks() { return java.util.Collections.unmodifiableSet(activeBlocks); }
}
