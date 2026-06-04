package nl.kmc.quake.weapons;

import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.util.Sfx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Jump Pad Grenade — thrown gadget that creates a temporary launch pad where
 * it lands. Any player who steps on it (owner or enemy) is launched upward.
 * Pure mobility/utility, no damage.
 */
public final class JumpPadGrenadeWeapon {

    private JumpPadGrenadeWeapon() {}

    public static boolean throwPad(QuakeCraftPlugin plugin, Player thrower) {
        ItemStack stack = new ItemStack(org.bukkit.Material.SLIME_BALL);
        Location origin = thrower.getEyeLocation();
        Item item = thrower.getWorld().dropItem(origin, stack);
        item.setVelocity(origin.getDirection().multiply(0.9).add(new Vector(0, 0.2, 0)));
        item.setPickupDelay(Integer.MAX_VALUE);

        Sfx.play(plugin, origin, "jump_pad_grenade.throw", Sound.ENTITY_SLIME_JUMP, 1f, 1.2f);

        int fuse = plugin.getConfig().getInt("powerups.jump_pad_grenade.fuse-ticks", 16);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location at = item.isValid() ? item.getLocation() : origin;
            if (item.isValid()) item.remove();
            deploy(plugin, at);
        }, fuse);
        return true;
    }

    private static void deploy(QuakeCraftPlugin plugin, Location pad) {
        if (pad.getWorld() == null) return;

        int lifetimeSec = plugin.getConfig().getInt("powerups.jump_pad_grenade.lifetime-seconds", 8);
        double radius   = plugin.getConfig().getDouble("powerups.jump_pad_grenade.radius", 1.5);
        double strength = plugin.getConfig().getDouble("powerups.jump_pad_grenade.launch-strength", 1.1);

        Sfx.play(plugin, pad, "jump_pad_grenade.deploy", Sound.BLOCK_SLIME_BLOCK_PLACE, 1f, 1f);

        new BukkitRunnable() {
            int ticks = lifetimeSec * 20;

            @Override
            public void run() {
                if (ticks <= 0 || pad.getWorld() == null) { cancel(); return; }
                ticks -= 5;

                pad.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, pad.clone().add(0, 0.2, 0),
                        6, radius * 0.5, 0.1, radius * 0.5, 0);

                double rSq = radius * radius;
                for (var e : pad.getWorld().getNearbyEntities(pad, radius, 1.2, radius)) {
                    if (!(e instanceof Player pl) || pl.isDead()) continue;
                    if (pl.getLocation().distanceSquared(pad) > rSq + 1.0) continue;
                    if (pl.getVelocity().getY() > 0.3) continue; // already going up — don't stack
                    Vector v = pl.getLocation().getDirection().setY(0);
                    if (v.lengthSquared() > 0) v.normalize().multiply(0.25);
                    v.setY(strength);
                    pl.setVelocity(v);
                    pl.setFallDistance(0f);
                    pl.getWorld().playSound(pl.getLocation(), Sound.ENTITY_SLIME_SQUISH, 0.8f, 1.4f);
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }
}
