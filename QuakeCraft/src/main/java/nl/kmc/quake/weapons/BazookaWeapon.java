package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Bazooka — fires a forward-travelling rocket that detonates on impact
 * (block or player) with an area-of-effect blast, like a directed grenade.
 *
 * <p>Implementation: ray-marches a point forward each tick, drawing a flame
 * trail. When it hits a solid block, passes near a player, or reaches max
 * range, it detonates and ray-checks every player in the blast radius
 * (line-of-sight gated, same as the grenade) crediting kills to the shooter.
 *
 * <p>Has a cooldown (like the other weapons) and a limited number of uses
 * tracked by {@link PlayerState} / {@code ActivePowerup}.
 */
public final class BazookaWeapon {

    private BazookaWeapon() {}

    /**
     * Fires the bazooka. Returns {@code true} if a rocket was launched
     * (cooldown elapsed), {@code false} if still on cooldown — mirroring the
     * other powerup weapons so the caller knows whether to consume a use.
     */
    public static boolean fire(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.bazooka.cooldown-ms", 2500);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        final double range  = plugin.getConfig().getDouble("powerups.bazooka.max-range", 60);
        final double speed  = plugin.getConfig().getDouble("powerups.bazooka.projectile-speed", 1.2);
        final double radius = plugin.getConfig().getDouble("powerups.bazooka.explosion-radius", 4.5);

        World world = shooter.getWorld();
        Vector dir  = shooter.getEyeLocation().getDirection().normalize();
        Location point = shooter.getEyeLocation().add(dir.clone().multiply(0.8));

        world.playSound(shooter.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.6f);
        world.playSound(shooter.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);

        new BukkitRunnable() {
            double travelled = 0;

            @Override
            public void run() {
                if (!shooter.isOnline()) { cancel(); return; }

                for (int sub = 0; sub < 2; sub++) { // 2 sub-steps per tick for smoother collision
                    point.add(dir.clone().multiply(speed / 2.0));
                    travelled += speed / 2.0;

                    // Trail
                    world.spawnParticle(Particle.FLAME, point, 2, 0.05, 0.05, 0.05, 0);
                    world.spawnParticle(Particle.SMOKE, point, 1, 0.05, 0.05, 0.05, 0);

                    // Hit a solid block?
                    if (point.getBlock().getType().isSolid()) {
                        detonate(plugin, shooter, point.clone(), radius);
                        cancel(); return;
                    }
                    // Near a player (not the shooter)?
                    for (var e : world.getNearbyEntities(point, 1.5, 1.5, 1.5)) {
                        if (e instanceof Player pl && !pl.equals(shooter) && !pl.isDead()) {
                            detonate(plugin, shooter, point.clone(), radius);
                            cancel(); return;
                        }
                    }
                    // Out of range?
                    if (travelled >= range) {
                        detonate(plugin, shooter, point.clone(), radius);
                        cancel(); return;
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    private static void detonate(QuakeCraftPlugin plugin, Player shooter, Location loc, double radius) {
        World world = loc.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.EXPLOSION, loc, 1);
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 12, 0.6, 0.6, 0.6, 0);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.9f);

        double radiusSq = radius * radius;
        for (var entity : world.getNearbyEntities(loc, radius, radius, radius)) {
            if (!(entity instanceof Player target)) continue;
            if (target.isDead() || target.equals(shooter)) continue;
            if (target.getLocation().distanceSquared(loc) > radiusSq) continue;

            // Line-of-sight gate: a wall between the blast and the target blocks it.
            Vector toTarget = target.getEyeLocation().toVector().subtract(loc.toVector());
            if (world.rayTraceBlocks(loc, toTarget, toTarget.length()) != null) continue;

            if (plugin.getGameManagerV2() != null) {
                plugin.getGameManagerV2().handleHit(shooter, target, "bazooka");
            }
        }
    }
}
