package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.models.PowerupType;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * The railgun firing logic — instant raytrace from the player's eye
 * to find a target. Handles the base wooden-hoe railgun plus the
 * powerup variants (sniper, shotgun, machine gun).
 *
 * <p>For grenades, see {@link nl.kmc.quake.weapons.GrenadeWeapon}.
 * For speed buff, see the listener — no projectile.
 */
public final class RailgunWeapon {

    private RailgunWeapon() {}

    /**
     * Fire the base railgun (wooden hoe).
     *
     * @return true if the shot was fired (cooldown ok), false otherwise
     */
    public static boolean fireBase(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("game.railgun-cooldown-ms", 1500);
        if (!state.canShoot(cd)) {
            long remaining = state.msUntilNextShot(cd);
            shooter.sendActionBar(net.kyori.adventure.text.Component.text(
                    ChatColor.RED + "Cooldown: " + (remaining / 100) / 10.0 + "s"));
            return false;
        }
        state.markShot();

        double range = plugin.getConfig().getDouble("game.railgun-max-range", 80);
        fireRay(plugin, shooter, range, "railgun");
        playShot(shooter, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.6f);
        return true;
    }

    /**
     * Fire the sniper — long range + tracer particle line.
     */
    public static boolean fireSniper(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.sniper.cooldown-ms", 2000);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        double range = plugin.getConfig().getDouble("powerups.sniper.max-range", 200);
        boolean tracer = plugin.getConfig().getBoolean("powerups.sniper.show-tracer", true);
        fireRay(plugin, shooter, range, "sniper");
        if (tracer) drawTracer(shooter, range);
        playShot(shooter, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.5f);
        return true;
    }

    /**
     * Fire the shotgun — N rays in a cone.
     */
    public static boolean fireShotgun(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.shotgun.cooldown-ms", 800);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        int    pellets = plugin.getConfig().getInt("powerups.shotgun.pellets", 5);
        double spread  = plugin.getConfig().getDouble("powerups.shotgun.spread-degrees", 8);
        double range   = plugin.getConfig().getDouble("powerups.shotgun.max-range", 25);

        Vector aim = shooter.getEyeLocation().getDirection();
        for (int i = 0; i < pellets; i++) {
            Vector pellet = applySpread(aim, spread);
            fireRay(plugin, shooter, range, "shotgun", pellet);
        }
        playShot(shooter, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        return true;
    }

    /**
     * Fire the machine gun — fast small range.
     */
    public static boolean fireMachineGun(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.machine_gun.cooldown-ms", 200);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        double range = plugin.getConfig().getDouble("powerups.machine_gun.max-range", 60);
        fireRay(plugin, shooter, range, "machine_gun");
        playShot(shooter, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 2.0f);
        return true;
    }

    // ----------------------------------------------------------------
    // Core raytrace
    // ----------------------------------------------------------------

    private static void fireRay(QuakeCraftPlugin plugin, Player shooter, double range, String reason) {
        fireRay(plugin, shooter, range, reason, shooter.getEyeLocation().getDirection());
    }

    private static void fireRay(QuakeCraftPlugin plugin, Player shooter, double range,
                                String reason, Vector direction) {
        Location origin = shooter.getEyeLocation();
        World world = origin.getWorld();
        if (world == null) return;

        RayTraceResult result = world.rayTrace(
                origin, direction, range,
                org.bukkit.FluidCollisionMode.NEVER, true, 0.3,
                e -> e instanceof Player && !e.equals(shooter) && !e.isDead()
        );

        // Visual: small redstone particle at the impact point
        if (result != null && result.getHitPosition() != null) {
            world.spawnParticle(Particle.CRIT,
                    result.getHitPosition().toLocation(world), 5, 0.1, 0.1, 0.1, 0);
        }

        // Did we hit a player?
        if (result != null && result.getHitEntity() instanceof Player target) {
            plugin.getGameManager().handleHit(shooter, target, reason);
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static Vector applySpread(Vector base, double maxDegrees) {
        double yawSpread   = (Math.random() - 0.5) * 2 * Math.toRadians(maxDegrees);
        double pitchSpread = (Math.random() - 0.5) * 2 * Math.toRadians(maxDegrees);

        Vector result = base.clone();
        result.rotateAroundY(yawSpread);
        // Apply pitch by rotating around the local X axis (perpendicular to direction)
        Vector right = base.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        result.rotateAroundAxis(right, pitchSpread);
        return result.normalize();
    }

    private static void drawTracer(Player shooter, double range) {
        Location start = shooter.getEyeLocation();
        Vector dir = start.getDirection();
        World world = start.getWorld();
        if (world == null) return;
        for (double d = 1.0; d < range; d += 0.5) {
            Location point = start.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
        }
    }

    private static void playShot(Player shooter, Sound s, float volume, float pitch) {
        shooter.getWorld().playSound(shooter.getLocation(), s, volume, pitch);
    }
}
