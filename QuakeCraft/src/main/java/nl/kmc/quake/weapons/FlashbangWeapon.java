package nl.kmc.quake.weapons;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.util.Sfx;
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
import org.bukkit.util.Vector;

/**
 * Flashbang — throw, short fuse, then a blinding flash. Players who are
 * looking toward the blast (and within range) get blinded; those facing away
 * are spared. No damage.
 */
public final class FlashbangWeapon {

    private FlashbangWeapon() {}

    public static boolean throwBang(QuakeCraftPlugin plugin, Player thrower) {
        ItemStack stack = new ItemStack(org.bukkit.Material.ECHO_SHARD);
        Location origin = thrower.getEyeLocation();
        Item item = thrower.getWorld().dropItem(origin, stack);
        item.setVelocity(origin.getDirection().multiply(1.2).add(new Vector(0, 0.2, 0)));
        item.setPickupDelay(Integer.MAX_VALUE);

        Sfx.play(plugin, origin, "flashbang.throw", Sound.ENTITY_SNOWBALL_THROW, 1f, 1.2f);
        // Metallic bounce while it travels
        int fuse = plugin.getConfig().getInt("powerups.flashbang.fuse-ticks", 20);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (item.isValid())
                Sfx.play(plugin, item.getLocation(), "flashbang.bounce", Sound.BLOCK_ANVIL_LAND, 0.4f, 1.8f);
        }, fuse / 2);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location at = item.isValid() ? item.getLocation() : origin;
            if (item.isValid()) item.remove();
            detonate(plugin, thrower, at);
        }, fuse);
        return true;
    }

    private static void detonate(QuakeCraftPlugin plugin, Player owner, Location loc) {
        if (loc.getWorld() == null) return;

        double maxDist  = plugin.getConfig().getDouble("powerups.flashbang.max-distance", 12);
        int durationSec = plugin.getConfig().getInt("powerups.flashbang.duration-seconds", 4);

        Sfx.play(plugin, loc, "flashbang.flash", Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 2.0f);
        try { loc.getWorld().spawnParticle(Particle.FLASH, loc, 4, org.bukkit.Color.WHITE); } catch (Exception ignored) { }
        loc.getWorld().spawnParticle(Particle.END_ROD, loc, 60, 0.4, 0.4, 0.4, 0.4);

        PotionEffectType blind = effect("blindness");
        PotionEffectType dark  = effect("darkness");
        int dur = durationSec * 20;
        double maxSq = maxDist * maxDist;

        for (var e : loc.getWorld().getNearbyEntities(loc, maxDist, maxDist, maxDist)) {
            if (!(e instanceof Player pl) || pl.isDead()) continue;
            if (pl.equals(owner) || nl.kmc.quake.util.TeamUtil.areTeammates(plugin, owner, pl)) continue;
            if (pl.getLocation().distanceSquared(loc) > maxSq) continue;

            // Everyone in range is revealed (Glowing, visible through walls) — the flashbang is a scouting
            // tool. Only those looking towards the flash are also blinded and slowed.
            PotionEffectType glow = effect("glowing");
            int revealTicks = plugin.getConfig().getInt("powerups.flashbang.reveal-seconds", 6) * 20;
            if (glow != null) pl.addPotionEffect(new PotionEffect(glow, revealTicks, 0, true, false, false));

            Vector toFlash = loc.toVector().subtract(pl.getEyeLocation().toVector()).normalize();
            Vector look    = pl.getEyeLocation().getDirection().normalize();
            if (look.dot(toFlash) < 0.25) continue; // facing away → spared the blinding
            PotionEffectType slow = effect("slowness");
            if (slow != null) pl.addPotionEffect(new PotionEffect(slow, dur, 1, true, false, false));

            if (blind != null) pl.addPotionEffect(new PotionEffect(blind, dur, 0, true, false, false));
            if (dark  != null) pl.addPotionEffect(new PotionEffect(dark,  dur, 0, true, false, false));
            Sfx.playTo(plugin, pl, "flashbang.ring", Sound.ITEM_TOTEM_USE, 0.6f, 2.0f);
        }
    }

    private static PotionEffectType effect(String key) {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT).get(NamespacedKey.minecraft(key)); }
        catch (Exception e) { return null; }
    }
}
