package nl.kmc.quake.weapons;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Smoke Bomb — thrown like a grenade; on landing it creates a dense smoke
 * cloud that blocks line of sight and blinds enemies who stand inside it.
 *
 * <p>Defensive / escape tool: no damage, but great for breaking sightlines
 * in a hitscan-heavy game.
 */
public final class SmokeBombWeapon {

    private SmokeBombWeapon() {}

    /** Always returns true (a use is consumed each throw). */
    public static boolean throwBomb(QuakeCraftPlugin plugin, Player thrower) {
        ItemStack stack = new ItemStack(org.bukkit.Material.GUNPOWDER);
        Location origin = thrower.getEyeLocation();
        Item item = thrower.getWorld().dropItem(origin, stack);
        item.setVelocity(origin.getDirection().multiply(1.0).add(new Vector(0, 0.15, 0)));
        item.setPickupDelay(Integer.MAX_VALUE);

        thrower.getWorld().playSound(origin, Sound.ENTITY_SNOWBALL_THROW, 1f, 0.8f);

        int fuse = plugin.getConfig().getInt("powerups.smoke_bomb.fuse-ticks", 20);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location at = item.isValid() ? item.getLocation() : origin;
            if (item.isValid()) item.remove();
            deploySmoke(plugin, thrower, at);
        }, fuse);

        return true;
    }

    private static void deploySmoke(QuakeCraftPlugin plugin, Player owner, Location center) {
        if (center.getWorld() == null) return;

        int durationSec = plugin.getConfig().getInt("powerups.smoke_bomb.duration-seconds", 6);
        double radius   = plugin.getConfig().getDouble("powerups.smoke_bomb.radius", 4.0);
        PotionEffectType blindness = effect("blindness");

        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.8f);

        new BukkitRunnable() {
            int ticksLeft = durationSec * 20;

            @Override
            public void run() {
                if (ticksLeft <= 0 || center.getWorld() == null) { cancel(); return; }
                ticksLeft -= 5;

                // Dense smoke cloud
                center.getWorld().spawnParticle(Particle.LARGE_SMOKE,
                        center.clone().add(0, 1, 0), 40, radius * 0.6, 1.2, radius * 0.6, 0.01);
                center.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                        center.clone().add(0, 0.5, 0), 6, radius * 0.5, 0.5, radius * 0.5, 0.0);

                // Blind enemies standing in the cloud
                if (blindness != null) {
                    double rSq = radius * radius;
                    for (var e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                        if (!(e instanceof Player pl) || pl.isDead()) continue;
                        if (pl.equals(owner) || nl.kmc.quake.util.TeamUtil.areTeammates(plugin, owner, pl)) continue;
                        if (pl.getLocation().distanceSquared(center) > rSq) continue;
                        pl.addPotionEffect(new PotionEffect(blindness, 30, 0, true, false, false));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private static PotionEffectType effect(String key) {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT)
                .get(NamespacedKey.minecraft(key)); }
        catch (Exception e) { return null; }
    }
}
