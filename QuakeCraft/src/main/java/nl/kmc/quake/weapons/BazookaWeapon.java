package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.TeamUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Bazooka — fires a forward-travelling rocket. On impact it does NOT do an
 * area-of-effect blast; instead it bursts into a <b>railgun nova</b>: a spray
 * of hitscan railgun rays radiating outward in every direction (~10 blocks
 * each). Any enemy a ray crosses takes a railgun hit. Teammates are ignored
 * by every ray via {@link TeamUtil} — friendly fire is impossible.
 *
 * <p>Implementation: ray-marches a point forward each tick, drawing a flame
 * trail. When it hits a solid block, passes near an enemy, or reaches max
 * range, it bursts into the nova.
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

        final double range     = plugin.getConfig().getDouble("powerups.bazooka.max-range", 60);
        final double speed     = plugin.getConfig().getDouble("powerups.bazooka.projectile-speed", 1.2);
        final double rayLength = plugin.getConfig().getDouble("powerups.bazooka.ray-length", 7);
        final int    rayCount  = plugin.getConfig().getInt("powerups.bazooka.ray-count", 24);

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

                    // Hit a solid block? Burst slightly BACK in the air so the
                    // fragment rays don't start inside the block (which would
                    // block them all instantly = no visible fragments).
                    if (point.getBlock().getType().isSolid()) {
                        Location burstAt = point.clone().subtract(dir.clone().multiply(0.8));
                        burst(plugin, shooter, burstAt, rayLength, rayCount);
                        cancel(); return;
                    }
                    // Near an enemy (teammates don't trigger it)?
                    for (var e : world.getNearbyEntities(point, 1.5, 1.5, 1.5)) {
                        if (e instanceof Player pl && !pl.isDead() && TeamUtil.isEnemy(plugin, shooter, pl)) {
                            burst(plugin, shooter, point.clone(), rayLength, rayCount);
                            cancel(); return;
                        }
                    }
                    // Out of range?
                    if (travelled >= range) {
                        burst(plugin, shooter, point.clone(), rayLength, rayCount);
                        cancel(); return;
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    /**
     * The nova is the shared {@link RailgunNova} fragment burst.
     */
    private static void burst(QuakeCraftPlugin plugin, Player shooter, Location center,
                              double rayLength, int rayCount) {
        RailgunNova.fire(plugin, shooter, center, rayLength, rayCount, "bazooka");
    }
}
