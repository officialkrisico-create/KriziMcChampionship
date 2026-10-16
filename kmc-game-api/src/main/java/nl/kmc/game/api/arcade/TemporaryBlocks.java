package nl.kmc.game.api.arcade;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocks a game places for its own use (machines, stations, doors). The original block data is remembered
 * the first time a position is touched, so {@link #restoreAll()} puts the arena back exactly as it was, also
 * when a position was changed more than once. Always call it from the game's cleanup.
 */
public final class TemporaryBlocks {

    private final Map<Long, BlockData> original = new LinkedHashMap<>();
    private final Map<Long, Block> touched = new LinkedHashMap<>();

    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12) | (b.getY() & 0xFFFL);
    }

    public void set(Block b, Material m) {
        long k = key(b);
        original.putIfAbsent(k, b.getBlockData());
        touched.put(k, b);
        b.setType(m, false);
    }

    public void set(Location l, Material m) { set(l.getBlock(), m); }

    public void setData(Block b, BlockData d) {
        long k = key(b);
        original.putIfAbsent(k, b.getBlockData());
        touched.put(k, b);
        b.setBlockData(d, false);
    }

    public int size() { return touched.size(); }

    /** Puts every touched block back. */
    public void restoreAll() {
        for (var en : touched.entrySet()) {
            BlockData d = original.get(en.getKey());
            if (d != null) en.getValue().setBlockData(d, false);
        }
        touched.clear();
        original.clear();
    }
}
