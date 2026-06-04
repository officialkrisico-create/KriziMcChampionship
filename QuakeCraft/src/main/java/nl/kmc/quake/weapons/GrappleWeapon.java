package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Grappling Hook — pulls the player toward the block they're looking at.
 *
 * <p>Pure mobility tool (no damage). Great for reaching jump-pad ledges or
 * escaping a fight. Ray-traces to a block within range, then throws the player
 * toward it with an upward bias so they arc onto the surface.
 */
public final class GrappleWeapon {

    private GrappleWeapon() {}

    /** @return true if a grapple fired (cooldown elapsed and a block was hit). */
    public static boolean fire(QuakeCraftPlugin plugin, Player p, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.grapple.cooldown-ms", 1000);
        if (!state.canShoot(cd)) return false;

        double range    = plugin.getConfig().getDouble("powerups.grapple.max-range", 30);
        double strength = plugin.getConfig().getDouble("powerups.grapple.pull-strength", 1.4);
        double upBias   = plugin.getConfig().getDouble("powerups.grapple.up-bias", 0.35);

        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);

        if (hit == null || hit.getHitPosition() == null) {
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.GRAY + "Geen blok in bereik!"));
            return false; // nothing to grapple onto — don't waste a use
        }

        state.markShot();

        Location target = hit.getHitPosition().toLocation(p.getWorld());
        Vector pull = target.toVector().subtract(p.getLocation().toVector());
        double dist = pull.length();
        if (dist < 0.1) return false;
        pull.normalize().multiply(Math.min(strength, 0.3 + dist * 0.12));
        pull.setY(Math.max(pull.getY(), 0) + upBias); // always a little upward

        p.setVelocity(pull);
        p.setFallDistance(0f);

        // Visual rope line from player to anchor
        Vector dir = target.toVector().subtract(eye.toVector());
        double len = dir.length();
        dir.normalize();
        for (double d = 0; d < len; d += 0.5) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            p.getWorld().spawnParticle(Particle.CRIT, point, 1, 0, 0, 0, 0);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 1.2f);
        return true;
    }
}
