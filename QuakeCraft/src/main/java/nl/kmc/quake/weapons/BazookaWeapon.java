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
        final double rayLength = plugin.getConfig().getDouble("powerups.bazooka.ray-length", 10);
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

                    // Hit a solid block?
                    if (point.getBlock().getType().isSolid()) {
                        burst(plugin, shooter, point.clone(), rayLength, rayCount);
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
     * The nova: a visual explosion flash followed by {@code rayCount} railgun
     * rays fired outward in an even spherical spread, each up to {@code rayLength}
     * blocks. The first enemy each ray crosses (line-of-sight gated by blocks)
     * takes a railgun hit credited to the shooter. Teammates are skipped.
     */
    private static void burst(QuakeCraftPlugin plugin, Player shooter, Location center,
                              double rayLength, int rayCount) {
        World world = center.getWorld();
        if (world == null) return;

        // Visual + audio flash (no AoE damage — the rays do the damage).
        world.spawnParticle(Particle.EXPLOSION, center, 1);
        world.spawnParticle(Particle.FLASH, center, 1);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.1f);

        int rays = Math.max(1, rayCount);
        final double golden = Math.PI * (3.0 - Math.sqrt(5.0)); // golden angle for even sphere spread
        for (int i = 0; i < rays; i++) {
            double y = 1.0 - (i / (double) Math.max(1, rays - 1)) * 2.0; // 1 → -1
            double r = Math.sqrt(Math.max(0, 1.0 - y * y));
            double theta = golden * i;
            Vector rayDir = new Vector(Math.cos(theta) * r, y, Math.sin(theta) * r).normalize();
            fireRay(plugin, shooter, center, rayDir, rayLength);
        }
    }

    /** A single railgun ray from {@code center} along {@code dir}, up to {@code length} blocks. */
    private static void fireRay(QuakeCraftPlugin plugin, Player shooter, Location center,
                                Vector dir, double length) {
        World world = center.getWorld();
        if (world == null) return;

        // Hitscan for the first enemy along the ray (pass through teammates & shooter).
        RayTraceResult hit = world.rayTrace(center, dir, length, FluidCollisionMode.NEVER, true, 0.35,
                e -> e instanceof Player p && !p.isDead() && TeamUtil.isEnemy(plugin, shooter, p));

        double drawTo = length;
        if (hit != null && hit.getHitEntity() instanceof Player target) {
            drawTo = hit.getHitPosition().distance(center.toVector());
            if (plugin.getGameManagerV2() != null)
                plugin.getGameManagerV2().handleHit(shooter, target, "bazooka");
        } else if (hit != null && hit.getHitBlock() != null) {
            drawTo = hit.getHitPosition().distance(center.toVector());
        }

        // Tracer particles along the (possibly shortened) ray — railgun look.
        for (double d = 0.5; d <= drawTo; d += 0.6) {
            Location p = center.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.CRIT, p, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
        }
    }
}
