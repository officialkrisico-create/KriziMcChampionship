package nl.kmc.kmccore.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the chunks around a point asynchronously, a few per tick, so an arena is ready before players get
 * teleported into it (no stutter at game start). Calls {@code done} on the main thread when every chunk is
 * loaded, or after {@code timeoutTicks} at the latest — it never blocks a start.
 */
public final class ChunkPreloader {

    private ChunkPreloader() { }

    /**
     * @param radiusBlocks area to cover around {@code centre} (square)
     * @param perTick      how many chunk requests to start per tick
     */
    public static void preload(Plugin plugin, Location centre, int radiusBlocks, int perTick, int timeoutTicks,
                               Runnable done) {
        World w = centre != null ? centre.getWorld() : null;
        if (w == null) { done.run(); return; }
        int cx = centre.getBlockX() >> 4, cz = centre.getBlockZ() >> 4;
        int r = Math.max(0, Math.min(16, (int) Math.ceil(radiusBlocks / 16.0)));

        Queue<int[]> todo = new ArrayDeque<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (!w.isChunkLoaded(cx + dx, cz + dz)) todo.add(new int[] {cx + dx, cz + dz});
        if (todo.isEmpty()) { done.run(); return; }

        AtomicInteger pending = new AtomicInteger(todo.size());
        final boolean[] finished = {false};
        final BukkitTask[] task = new BukkitTask[1];
        final int[] waited = {0};

        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (finished[0]) { task[0].cancel(); return; }
            for (int i = 0; i < perTick && !todo.isEmpty(); i++) {
                int[] c = todo.poll();
                w.getChunkAtAsync(c[0], c[1], true).whenComplete((chunk, err) -> pending.decrementAndGet());
            }
            waited[0]++;
            if ((todo.isEmpty() && pending.get() <= 0) || waited[0] >= timeoutTicks) {
                finished[0] = true;
                task[0].cancel();
                done.run();
            }
        }, 1L, 1L);
    }
}
