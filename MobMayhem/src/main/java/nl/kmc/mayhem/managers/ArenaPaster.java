package nl.kmc.mayhem.managers;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Captures the admin-defined arena box (pos1/pos2, built in a normal world)
 * into an in-memory WorldEdit clipboard, and pastes it into the shared void
 * world at a given origin. Re-captured fresh each game start, so an admin's
 * in-progress arena edits are always picked up without a separate "recapture"
 * step.
 */
public final class ArenaPaster {

    private ArenaPaster() {}

    /** Captures the cuboid between {@code pos1} and {@code pos2} (inclusive, order-independent). */
    public static Clipboard capture(Location pos1, Location pos2) {
        World world = pos1.getWorld();
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);

        BlockVector3 min = BlockVector3.at(
                Math.min(pos1.getBlockX(), pos2.getBlockX()),
                Math.min(pos1.getBlockY(), pos2.getBlockY()),
                Math.min(pos1.getBlockZ(), pos2.getBlockZ()));
        BlockVector3 max = BlockVector3.at(
                Math.max(pos1.getBlockX(), pos2.getBlockX()),
                Math.max(pos1.getBlockY(), pos2.getBlockY()),
                Math.max(pos1.getBlockZ(), pos2.getBlockZ()));

        CuboidRegion region = new CuboidRegion(weWorld, min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).build()) {
            ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, min);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        } catch (Exception e) {
            throw new RuntimeException("Failed to capture Mob Mayhem arena region", e);
        }
        return clipboard;
    }

    /** Pastes {@code clipboard} so its minimum corner lands exactly at {@code destMin}. */
    public static void pasteAtMinCorner(Clipboard clipboard, Location destMin) {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(destMin.getWorld());
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 to = BlockVector3.at(destMin.getBlockX(), destMin.getBlockY(), destMin.getBlockZ())
                .add(origin.subtract(min));

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).build()) {
            Operation op = new ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(to)
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(op);
        } catch (Exception e) {
            throw new RuntimeException("Failed to paste Mob Mayhem arena", e);
        }
    }

    /** Clears a team's pocket back to air — same bounding box size as the captured arena, offset by {@code destMin}. */
    public static void clearPocket(World world, Location destMin, int dx, int dy, int dz) {
        int minX = destMin.getBlockX(), minY = destMin.getBlockY(), minZ = destMin.getBlockZ();
        for (int x = 0; x < dx; x++) {
            for (int y = 0; y < dy; y++) {
                for (int z = 0; z < dz; z++) {
                    var block = world.getBlockAt(minX + x, minY + y, minZ + z);
                    if (block.getType() != org.bukkit.Material.AIR) {
                        block.setType(org.bukkit.Material.AIR, false);
                    }
                }
            }
        }
    }
}
