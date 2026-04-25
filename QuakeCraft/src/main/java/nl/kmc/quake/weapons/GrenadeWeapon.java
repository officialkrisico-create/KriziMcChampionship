package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Grenade weapon — bone item that gets thrown, fuses for 2 seconds,
 * then explodes raytracing nearby players.
 *
 * <p>Implementation: spawn an Item entity going in the direction of
 * the throw, schedule a task that detonates it after fuse-ticks,
 * raytraces all players within radius, and credits kills to the thrower.
 */
public final class GrenadeWeapon {

    private GrenadeWeapon() {}

    public static void throwGrenade(QuakeCraftPlugin plugin, Player thrower) {
        ItemStack stack = new ItemStack(org.bukkit.Material.BONE);
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text("Grenade"));
            stack.setItemMeta(meta);
        }

        Location origin = thrower.getEyeLocation();
        Item item = thrower.getWorld().dropItem(origin, stack);
        item.setVelocity(origin.getDirection().multiply(1.2).add(new Vector(0, 0.2, 0)));
        item.setPickupDelay(Integer.MAX_VALUE);

        // Tag the item with thrower UUID
        var key = new org.bukkit.NamespacedKey(plugin, "grenade_thrower");
        item.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                thrower.getUniqueId().toString());

        thrower.getWorld().playSound(origin, Sound.ENTITY_SNOWBALL_THROW, 1f, 1f);

        int fuse = plugin.getConfig().getInt("powerups.grenade.fuse-ticks", 40);
        double radius = plugin.getConfig().getDouble("powerups.grenade.explosion-radius", 4.0);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (item.isDead() || !item.isValid()) return;
            Location boomLoc = item.getLocation();
            item.remove();
            detonate(plugin, thrower, boomLoc, radius);
        }, fuse);

        // Visual fuse particles
        for (int t = 0; t < fuse; t += 5) {
            int delay = t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (item.isDead() || !item.isValid()) return;
                item.getWorld().spawnParticle(Particle.SMOKE, item.getLocation(), 3, 0.1, 0.1, 0.1, 0);
            }, delay);
        }
    }

    private static void detonate(QuakeCraftPlugin plugin, Player thrower, Location loc, double radius) {
        var world = loc.getWorld();
        if (world == null) return;

        // Visual + audio
        world.spawnParticle(Particle.EXPLOSION, loc, 1);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1f);

        double radiusSq = radius * radius;
        for (var entity : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(entity instanceof Player target)) continue;
            if (target.isDead()) continue;
            if (target.equals(thrower)) continue;
            if (target.getLocation().distanceSquared(loc) > radiusSq) continue;

            // Line-of-sight check: only hit if there's a clear ray to them
            Vector toTarget = target.getEyeLocation().toVector().subtract(loc.toVector());
            var rayResult = world.rayTraceBlocks(loc, toTarget, toTarget.length());
            if (rayResult != null) continue; // wall in the way

            plugin.getGameManager().handleHit(thrower, target, "grenade");
        }
    }
}
