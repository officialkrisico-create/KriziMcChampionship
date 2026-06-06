package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.util.TeamUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Shared "railgun nova" fragment burst: an explosion flash followed by a spray
 * of hitscan railgun rays radiating outward in every direction. Used by the
 * grenade (length ~5) and the bazooka/rocket launcher (length ~7) so both feel
 * like a shrapnel blast of standard railgun bullets.
 */
public final class RailgunNova {

    private RailgunNova() {}

    /**
     * Fires {@code rayCount} railgun rays in an even spherical spread from
     * {@code center}, each up to {@code length} blocks. The first enemy each ray
     * crosses (line-of-sight gated) takes a railgun hit credited to {@code shooter}
     * under {@code weapon}. Teammates and the shooter are passed through.
     */
    public static void fire(QuakeCraftPlugin plugin, Player shooter, Location center,
                            double length, int rayCount, String weapon) {
        World world = center.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.EXPLOSION, center, 1);
        world.spawnParticle(Particle.FLASH, center, 1);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.1f);

        int rays = Math.max(1, rayCount);
        final double golden = Math.PI * (3.0 - Math.sqrt(5.0)); // golden angle = even sphere spread
        for (int i = 0; i < rays; i++) {
            double y = 1.0 - (i / (double) Math.max(1, rays - 1)) * 2.0; // 1 → -1
            double r = Math.sqrt(Math.max(0, 1.0 - y * y));
            double theta = golden * i;
            Vector dir = new Vector(Math.cos(theta) * r, y, Math.sin(theta) * r).normalize();
            fireRay(plugin, shooter, center, dir, length, weapon);
        }
    }

    private static void fireRay(QuakeCraftPlugin plugin, Player shooter, Location center,
                                Vector dir, double length, String weapon) {
        World world = center.getWorld();
        if (world == null) return;

        RayTraceResult hit = world.rayTrace(center, dir, length, FluidCollisionMode.NEVER, true, 0.35,
                e -> e instanceof Player p && !p.isDead() && TeamUtil.isEnemy(plugin, shooter, p));

        double drawTo = length;
        if (hit != null && hit.getHitEntity() instanceof Player target) {
            drawTo = hit.getHitPosition().distance(center.toVector());
            if (plugin.getGameManagerV2() != null)
                plugin.getGameManagerV2().handleHit(shooter, target, weapon);
        } else if (hit != null && hit.getHitBlock() != null) {
            drawTo = hit.getHitPosition().distance(center.toVector());
        }

        for (double d = 0.5; d <= drawTo; d += 0.6) {
            Location p = center.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.CRIT, p, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
        }
    }
}
