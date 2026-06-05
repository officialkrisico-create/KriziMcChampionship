package nl.kmc.quake.weapons;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.util.Sfx;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Freeze Grenade — crowd control. Explodes in an area and chills enemies:
 * heavy slowness + crippled jump height for a duration. No damage.
 */
public final class FreezeGrenadeWeapon {

    private FreezeGrenadeWeapon() {}

    public static boolean throwBomb(QuakeCraftPlugin plugin, Player thrower) {
        ItemStack stack = new ItemStack(org.bukkit.Material.PACKED_ICE);
        Location origin = thrower.getEyeLocation();
        Item item = thrower.getWorld().dropItem(origin, stack);
        item.setVelocity(origin.getDirection().multiply(1.1).add(new Vector(0, 0.2, 0)));
        item.setPickupDelay(Integer.MAX_VALUE);

        Sfx.play(plugin, origin, "freeze_grenade.throw", Sound.BLOCK_GLASS_BREAK, 0.8f, 1.5f);

        int fuse = plugin.getConfig().getInt("powerups.freeze_grenade.fuse-ticks", 24);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location at = item.isValid() ? item.getLocation() : origin;
            if (item.isValid()) item.remove();
            detonate(plugin, thrower, at);
        }, fuse);
        return true;
    }

    private static void detonate(QuakeCraftPlugin plugin, Player owner, Location loc) {
        if (loc.getWorld() == null) return;

        double radius   = plugin.getConfig().getDouble("powerups.freeze_grenade.radius", 4.5);
        int durationSec = plugin.getConfig().getInt("powerups.freeze_grenade.duration-seconds", 4);
        int slowAmp     = plugin.getConfig().getInt("powerups.freeze_grenade.slow-amplifier", 3);

        Sfx.play(plugin, loc, "freeze_grenade.burst", Sound.BLOCK_GLASS_BREAK, 1.2f, 0.8f);
        loc.getWorld().spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1, 0),
                60, radius * 0.6, 1.0, radius * 0.6, 0.02);
        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0),
                40, radius * 0.6, 1.0, radius * 0.6, new Particle.DustOptions(Color.AQUA, 1.5f));

        PotionEffectType slow = effect("slowness");
        PotionEffectType jump = effect("jump_boost");
        int dur = durationSec * 20;
        double rSq = radius * radius;

        for (var e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!(e instanceof Player pl) || pl.isDead()) continue;
            if (pl.equals(owner) || nl.kmc.quake.util.TeamUtil.areTeammates(plugin, owner, pl)) continue;
            if (pl.getLocation().distanceSquared(loc) > rSq) continue;
            if (slow != null) pl.addPotionEffect(new PotionEffect(slow, dur, slowAmp, true, true, true));
            // jump_boost amplifier 128 cripples jump height (vanilla overflow trick)
            if (jump != null) pl.addPotionEffect(new PotionEffect(jump, dur, 128, true, false, false));
            pl.playSound(pl.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 1f, 0.6f);
        }
    }

    private static PotionEffectType effect(String key) {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT).get(NamespacedKey.minecraft(key)); }
        catch (Exception e) { return null; }
    }
}
