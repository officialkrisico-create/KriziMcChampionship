package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.Sfx;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.Random;

/**
 * Airstrike — area denial & spectacle. Marks the spot you're looking at, then
 * after a delay rains several instant-kill explosions across a radius.
 *
 * <p>This is the one new gadget that CAN kill — it's the "legendary" area
 * threat. Kills are credited to the caller through the game manager.
 */
public final class AirstrikeWeapon {

    private static final Random RNG = new Random();

    private AirstrikeWeapon() {}

    public static boolean fire(QuakeCraftPlugin plugin, Player caller, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.airstrike.cooldown-ms", 1000);
        if (!state.canShoot(cd)) return false;

        double range = plugin.getConfig().getDouble("powerups.airstrike.mark-range", 80);
        Location eye = caller.getEyeLocation();
        RayTraceResult hit = caller.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location target = (hit != null && hit.getHitPosition() != null)
                ? hit.getHitPosition().toLocation(caller.getWorld())
                : eye.clone().add(eye.getDirection().multiply(range));

        state.markShot();

        int delay      = plugin.getConfig().getInt("powerups.airstrike.delay-ticks", 60);
        double radius  = plugin.getConfig().getDouble("powerups.airstrike.strike-radius", 6.0);
        int impacts    = plugin.getConfig().getInt("powerups.airstrike.impacts", 6);
        double killR   = plugin.getConfig().getDouble("powerups.airstrike.impact-radius", 3.0);

        // Marker + warning
        Sfx.play(plugin, target, "airstrike.marker", Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.5f);
        Sfx.playGlobal(plugin, "airstrike.incoming", Sound.EVENT_RAID_HORN, 0.7f, 1f);
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "☢ Airstrike inkomend bij "
                + target.getBlockX() + "," + target.getBlockZ() + "! Wegwezen!");

        // Beam-down marker particles during the countdown
        final Location markCenter = target.clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            int left = delay;
            @Override public void run() {
                if (left <= 0 || markCenter.getWorld() == null) { cancel(); return; }
                left -= 4;
                markCenter.getWorld().spawnParticle(Particle.DUST, markCenter.clone().add(0, 0.1, 0),
                        20, radius * 0.7, 0.1, radius * 0.7,
                        new Particle.DustOptions(org.bukkit.Color.RED, 2.0f));
            }
        }.runTaskTimer(plugin, 0L, 4L);

        // After the delay: stagger the impacts.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Sfx.play(plugin, markCenter, "airstrike.whistle", Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 0.6f);
            for (int i = 0; i < impacts; i++) {
                int idx = i;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    double ox = (RNG.nextDouble() - 0.5) * 2 * radius;
                    double oz = (RNG.nextDouble() - 0.5) * 2 * radius;
                    Location impact = markCenter.clone().add(ox, 0, oz);
                    strike(plugin, caller, impact, killR);
                }, (long) idx * 4L);
            }
        }, delay);

        return true;
    }

    private static void strike(QuakeCraftPlugin plugin, Player caller, Location loc, double killR) {
        if (loc.getWorld() == null) return;
        loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.8f);

        double rSq = killR * killR;
        for (var e : loc.getWorld().getNearbyEntities(loc, killR, killR, killR)) {
            if (!(e instanceof Player target) || target.isDead()) continue;
            if (target.getLocation().distanceSquared(loc) > rSq) continue;
            if (plugin.getGameManagerV2() != null) {
                plugin.getGameManagerV2().handleHit(caller, target, "airstrike");
            }
        }
    }
}
