package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import nl.kmc.mayhem.models.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Clears everything to air around a cloned arena except the arena itself —
 * so mobs/players can't wander off into the rest of the template world's
 * terrain. Opt-in (disabled unless {@code arena.voidify-margin} &gt; 0) since
 * it's a one-time but potentially large block-clearing pass per clone.
 *
 * <p>Only ever touches a disposable per-team clone, never the template.
 * Runs batched across ticks (configurable columns/tick) so it doesn't
 * freeze the server even for a sizeable margin.
 */
public final class ArenaVoider {

    private ArenaVoider() {}

    public static void voidifyAsync(MobMayhemPlugin plugin, World world, Arena arena, Runnable onComplete) {
        int margin = plugin.getConfig().getInt("arena.voidify-margin", 0);
        if (margin <= 0) { if (onComplete != null) onComplete.run(); return; }

        List<Location> points = new ArrayList<>();
        if (arena.getPlayerSpawn() != null) points.add(arena.getPlayerSpawn());
        points.addAll(arena.getMobSpawns());
        points.addAll(arena.getPowerupSpawns());
        if (points.isEmpty()) { if (onComplete != null) onComplete.run(); return; }

        int keepMinX = points.stream().mapToInt(Location::getBlockX).min().getAsInt();
        int keepMaxX = points.stream().mapToInt(Location::getBlockX).max().getAsInt();
        int keepMinZ = points.stream().mapToInt(Location::getBlockZ).min().getAsInt();
        int keepMaxZ = points.stream().mapToInt(Location::getBlockZ).max().getAsInt();
        int baseY    = (int) Math.round(points.stream().mapToInt(Location::getBlockY).average().orElse(64));

        // Pad the kept box a little so the arena itself isn't shaved at the edges.
        int keepPad = 6;
        keepMinX -= keepPad; keepMaxX += keepPad;
        keepMinZ -= keepPad; keepMaxZ += keepPad;

        int clearMinX = keepMinX - margin, clearMaxX = keepMaxX + margin;
        int clearMinZ = keepMinZ - margin, clearMaxZ = keepMaxZ + margin;
        int floorBelow   = plugin.getConfig().getInt("arena.voidify-floor-below", 10);
        int ceilingAbove = plugin.getConfig().getInt("arena.voidify-ceiling-above", 60);
        int minY = Math.max(world.getMinHeight(), baseY - floorBelow);
        int maxY = Math.min(world.getMaxHeight() - 1, baseY + ceilingAbove);

        List<int[]> columns = new ArrayList<>();
        for (int x = clearMinX; x <= clearMaxX; x++) {
            for (int z = clearMinZ; z <= clearMaxZ; z++) {
                if (x >= keepMinX && x <= keepMaxX && z >= keepMinZ && z <= keepMaxZ) continue;
                columns.add(new int[]{x, z});
            }
        }
        if (columns.isEmpty()) { if (onComplete != null) onComplete.run(); return; }

        plugin.getLogger().info("[MobMayhem] Voiding " + columns.size() + " column(s) ("
                + "y " + minY + ".." + maxY + ") around the arena in world " + world.getName() + "...");

        int batchSize = Math.max(1, plugin.getConfig().getInt("arena.voidify-columns-per-tick", 150));
        int[] index = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(columns.size(), index[0] + batchSize);
            for (int i = index[0]; i < end; i++) {
                int[] col = columns.get(i);
                for (int y = minY; y <= maxY; y++) {
                    var block = world.getBlockAt(col[0], y, col[1]);
                    if (block.getType() != Material.AIR) block.setType(Material.AIR, false);
                }
            }
            index[0] = end;
            if (index[0] >= columns.size()) {
                task[0].cancel();
                plugin.getLogger().info("[MobMayhem] Void pass complete for " + world.getName() + ".");
                if (onComplete != null) onComplete.run();
            }
        }, 0L, 1L);
    }
}
