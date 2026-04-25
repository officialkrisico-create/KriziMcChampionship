package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Railgun firing logic — instant raytrace from the player's eye.
 *
 * <p>Visible tracer line drawn with particles every shot. Different
 * particle types per weapon so players can tell what's being fired.
 */
public final class RailgunWeapon {

    private RailgunWeapon() {}

    // ----------------------------------------------------------------
    // Public fire methods
    // ----------------------------------------------------------------

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
        fireRay(plugin, shooter, range, "railgun",
                shooter.getEyeLocation().getDirection(), Particle.CRIT);
        playShot(shooter, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.6f);
        return true;
    }

    public static boolean fireSniper(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.sniper.cooldown-ms", 2000);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        double range = plugin.getConfig().getDouble("powerups.sniper.max-range", 200);
        fireRay(plugin, shooter, range, "sniper",
                shooter.getEyeLocation().getDirection(), Particle.END_ROD);
        playShot(shooter, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.5f);
        return true;
    }

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
            fireRay(plugin, shooter, range, "shotgun", pellet, Particle.FLAME);
        }
        playShot(shooter, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        return true;
    }

    /**
     * Machine gun — fires a burst of N shots per right-click with a small
     * delay between them, simulating sustained fire.
     *
     * <p>Why a burst? PlayerInteractEvent has a built-in ~200ms throttle
     * per item, so single-shot-per-click can never go faster than 5/sec.
     * A burst per click decouples fire rate from event rate.
     */
    public static boolean fireMachineGun(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.machine_gun.cooldown-ms", 200);
        if (!state.canShoot(cd)) return false;
        state.markShot();

        double range = plugin.getConfig().getDouble("powerups.machine_gun.max-range", 60);
        int burstCount = plugin.getConfig().getInt("powerups.machine_gun.burst-count", 3);
        int burstDelayTicks = plugin.getConfig().getInt("powerups.machine_gun.burst-delay-ticks", 2);

        // Fire first shot immediately
        fireRay(plugin, shooter, range, "machine_gun",
                shooter.getEyeLocation().getDirection(), Particle.SMOKE);
        playShot(shooter, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 2.0f);

        // Schedule the rest of the burst
        for (int i = 1; i < burstCount; i++) {
            int shotIndex = i;
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!shooter.isOnline() || shooter.isDead()) return;
                if (shooter.getGameMode() != org.bukkit.GameMode.ADVENTURE) return;
                // Verify they still hold a machine gun (so they can't fire after
                // running out of ammo or switching weapons)
                var current = shooter.getInventory().getItemInMainHand();
                String wid = nl.kmc.quake.util.WeaponFactory.getWeaponId(plugin, current);
                if (!"MACHINE_GUN".equals(wid)) return;

                fireRay(plugin, shooter, range, "machine_gun",
                        shooter.getEyeLocation().getDirection(), Particle.SMOKE);
                playShot(shooter, Sound.ENTITY_BLAZE_SHOOT, 0.6f, 2.0f);
            }, (long) burstDelayTicks * shotIndex);
        }
        return true;
    }

    // ----------------------------------------------------------------
    // Core raytrace + tracer
    // ----------------------------------------------------------------

    private static void fireRay(QuakeCraftPlugin plugin, Player shooter, double range,
                                String reason, Vector direction, Particle tracerParticle) {
        Location origin = shooter.getEyeLocation();
        World world = origin.getWorld();
        if (world == null) return;

        // Slight forward offset so the tracer doesn't start INSIDE the player
        Location rayStart = origin.clone().add(direction.clone().multiply(0.5));

        RayTraceResult result = world.rayTrace(
                rayStart, direction, range,
                FluidCollisionMode.NEVER, true, 0.4,
                e -> e instanceof Player && !e.equals(shooter) && !e.isDead()
        );

        // Determine where the shot ended (hit point or max range)
        double actualRange = range;
        if (result != null && result.getHitPosition() != null) {
            actualRange = result.getHitPosition().distance(rayStart.toVector());
        }

        // Draw the tracer particle line — every 0.5 blocks
        for (double d = 0; d < actualRange; d += 0.5) {
            Location point = rayStart.clone().add(direction.clone().multiply(d));
            world.spawnParticle(tracerParticle, point, 1, 0, 0, 0, 0);
        }

        // Hit point splash effect
        if (result != null && result.getHitPosition() != null) {
            Location hitLoc = result.getHitPosition().toLocation(world);
            world.spawnParticle(Particle.CRIT, hitLoc, 12, 0.2, 0.2, 0.2, 0);
            world.spawnParticle(Particle.LARGE_SMOKE, hitLoc, 5, 0.1, 0.1, 0.1, 0);
        }

        // Did we hit a player?
        if (result != null && result.getHitEntity() instanceof Player target) {
            plugin.getGameManager().handleHit(shooter, target, reason);
        }
    }

    private static Vector applySpread(Vector base, double maxDegrees) {
        double yawSpread   = (Math.random() - 0.5) * 2 * Math.toRadians(maxDegrees);
        double pitchSpread = (Math.random() - 0.5) * 2 * Math.toRadians(maxDegrees);

        Vector result = base.clone();
        result.rotateAroundY(yawSpread);
        Vector right = base.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        if (right.lengthSquared() > 0.001) {
            result.rotateAroundAxis(right, pitchSpread);
        }
        return result.normalize();
    }

    private static void playShot(Player shooter, Sound s, float volume, float pitch) {
        shooter.getWorld().playSound(shooter.getLocation(), s, volume, pitch);
    }
}
