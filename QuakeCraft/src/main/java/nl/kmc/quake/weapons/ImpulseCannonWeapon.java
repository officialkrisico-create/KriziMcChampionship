package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.Sfx;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Impulse Cannon — a non-lethal shockwave that displaces players.
 *
 * <p>Pushes enemies away from the impact point and shoves the shooter away too
 * (rocket-jump style — fire at the ground to launch yourself). No damage; pure
 * mobility and area control.
 */
public final class ImpulseCannonWeapon {

    private ImpulseCannonWeapon() {}

    public static boolean fire(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.impulse_cannon.cooldown-ms", 1500);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        double range    = plugin.getConfig().getDouble("powerups.impulse_cannon.max-range", 25);
        double radius   = plugin.getConfig().getDouble("powerups.impulse_cannon.radius", 5.0);
        double strength = plugin.getConfig().getDouble("powerups.impulse_cannon.knockback-strength", 1.6);
        double selfMult = plugin.getConfig().getDouble("powerups.impulse_cannon.self-knockback-multiplier", 1.2);

        Location eye = shooter.getEyeLocation();
        RayTraceResult hit = shooter.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location impact = (hit != null && hit.getHitPosition() != null)
                ? hit.getHitPosition().toLocation(shooter.getWorld())
                : eye.clone().add(eye.getDirection().multiply(range));

        Sfx.play(plugin, eye, "impulse_cannon.fire", Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.4f);
        Sfx.play(plugin, impact, "impulse_cannon.impact", Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
        impact.getWorld().spawnParticle(Particle.SONIC_BOOM, impact, 1);
        impact.getWorld().spawnParticle(Particle.EXPLOSION, impact, 1);

        double radiusSq = radius * radius;
        for (var e : impact.getWorld().getNearbyEntities(impact, radius, radius, radius)) {
            if (!(e instanceof Player target) || target.isDead()) continue;
            if (target.getLocation().distanceSquared(impact) > radiusSq) continue;

            Vector push = target.getLocation().toVector().subtract(impact.toVector());
            if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
            push.normalize().multiply(strength);

            // The shooter gets a stronger self-launch so ground-firing rocket-jumps.
            double mult = target.equals(shooter) ? selfMult : 1.0;
            push.multiply(mult).setY(Math.max(push.getY(), 0) + 0.5 * mult);

            target.setVelocity(target.getVelocity().add(push));
            target.setFallDistance(0f);
        }
        return true;
    }
}
