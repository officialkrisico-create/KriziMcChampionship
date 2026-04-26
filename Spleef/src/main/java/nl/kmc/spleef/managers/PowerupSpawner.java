package nl.kmc.spleef.managers;

import nl.kmc.spleef.SpleefPlugin;
import nl.kmc.spleef.models.Arena;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Periodically drops a stack of snowballs at a random spot on the
 * arena floor. Players walk over them to pick up; throwing knocks
 * other players around (via vanilla snowball knockback + custom
 * boost).
 *
 * <p>Dropped items are tagged with a metadata key so we know they're
 * Spleef powerups (vs random items dropped by players or anything
 * else in the world).
 */
public class PowerupSpawner {

    private final SpleefPlugin plugin;
    private BukkitTask spawnTask;
    private final Random rng = new Random();

    /** Item entity UUIDs that are powerups (for de-duping pickups). */
    private final Set<java.util.UUID> activePowerupIds = new HashSet<>();

    public PowerupSpawner(SpleefPlugin plugin) { this.plugin = plugin; }

    public void start(Arena arena) {
        if (!plugin.getConfig().getBoolean("powerups.enabled", true)) return;
        stop();

        int intervalSec = plugin.getConfig().getInt("powerups.interval-seconds", 25);
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> spawnOne(arena), 100L, intervalSec * 20L);
    }

    public void stop() {
        if (spawnTask != null) { spawnTask.cancel(); spawnTask = null; }
        // Remove any active dropped powerups from the world
        for (var id : activePowerupIds) {
            var ent = Bukkit.getEntity(id);
            if (ent != null) ent.remove();
        }
        activePowerupIds.clear();
    }

    public boolean isPowerupItem(java.util.UUID itemUuid) {
        return activePowerupIds.contains(itemUuid);
    }

    public void unregisterPickedUp(java.util.UUID itemUuid) {
        activePowerupIds.remove(itemUuid);
    }

    // ----------------------------------------------------------------

    private void spawnOne(Arena arena) {
        var layer = arena.getTopLayer();
        if (layer == null || arena.getWorld() == null) return;

        // Pick a random point on the floor — must still have a snow
        // block under it (don't drop into the void)
        int attempts = 0;
        while (attempts < 20) {
            int x = layer.getMinX() + rng.nextInt(layer.getMaxX() - layer.getMinX() + 1);
            int z = layer.getMinZ() + rng.nextInt(layer.getMaxZ() - layer.getMinZ() + 1);
            var blockBelow = arena.getWorld().getBlockAt(x, layer.getYLevel(), z);
            if (!plugin.getFloorManager().isFloorBlock(blockBelow)) {
                attempts++;
                continue;
            }

            int snowballCount = plugin.getConfig().getInt("powerups.snowball-count", 4);
            ItemStack stack = new ItemStack(Material.SNOWBALL, snowballCount);

            Location dropLoc = new Location(arena.getWorld(),
                    x + 0.5, layer.getYLevel() + 1.2, z + 0.5);
            Item entity = arena.getWorld().dropItem(dropLoc, stack);
            entity.setVelocity(new org.bukkit.util.Vector(0, 0.1, 0));
            entity.setUnlimitedLifetime(true);
            entity.setGlowing(true);
            entity.setCustomName(ChatColor.AQUA + "❄ Snowballs ❄");
            entity.setCustomNameVisible(true);

            activePowerupIds.add(entity.getUniqueId());

            // Sound + particle for everyone in the arena
            arena.getWorld().spawnParticle(Particle.SNOWFLAKE, dropLoc, 20,
                    0.5, 0.5, 0.5, 0.05);
            for (var p : arena.getWorld().getPlayers()) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
            }
            return;
        }
    }
}
