package nl.kmc.speedbuild.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Low-level WorldEdit conversions: clipboard dimensions, block-type lookup,
 * and pasting a clipboard so its minimum corner lands at a chosen world block.
 *
 * <p>Anchoring by the minimum corner (rather than WE's origin) is what makes
 * block-by-block comparison deterministic.
 */
public final class WorldEditAdapter {

    private WorldEditAdapter() {}

    /** {dx, dy, dz} block dimensions of a clipboard's region. */
    public static int[] dimensions(Clipboard clipboard) {
        Region r = clipboard.getRegion();
        BlockVector3 min = r.getMinimumPoint(), max = r.getMaximumPoint();
        return new int[]{
                max.x() - min.x() + 1,
                max.y() - min.y() + 1,
                max.z() - min.z() + 1
        };
    }

    /** Bukkit {@link Material} of the schematic block at an absolute clipboard point. */
    public static Material materialAt(Clipboard clipboard, BlockVector3 pt) {
        return BukkitAdapter.adapt(clipboard.getBlock(pt).getBlockType());
    }

    /**
     * Pastes the clipboard so its minimum corner is placed at {@code desiredMin}.
     * Runs synchronously (WE paste is blocking) — call on the main thread.
     */
    public static void pasteAtMinCorner(Clipboard clipboard, Location desiredMin, boolean ignoreAir) {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(desiredMin.getWorld());
        Region r = clipboard.getRegion();
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 origin = clipboard.getOrigin();
        // paste.to(X) places the ORIGIN at X, so offset by (origin - min) to land min at desiredMin.
        BlockVector3 to = BlockVector3.at(desiredMin.getBlockX(), desiredMin.getBlockY(), desiredMin.getBlockZ())
                .add(origin.subtract(min));

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld).maxBlocks(-1).build()) {
            Operation op = new ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(to)
                    .ignoreAirBlocks(ignoreAir)
                    .build();
            Operations.complete(op);
        } catch (Exception e) {
            throw new RuntimeException("Failed to paste schematic preview", e);
        }
    }
}
