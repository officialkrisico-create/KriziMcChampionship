package nl.kmc.quake.weapons;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.quake.QuakeCraftPlugin;
import nl.kmc.quake.models.PlayerState;
import nl.kmc.quake.util.Sfx;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

/**
 * Recon Dart — information gathering. Hit an enemy to make them glow (visible
 * through walls) for a configurable duration, exposing their position to your
 * team. No damage.
 */
public final class ReconDartWeapon {

    private ReconDartWeapon() {}

    public static boolean fire(QuakeCraftPlugin plugin, Player shooter, PlayerState state) {
        long cd = plugin.getConfig().getLong("powerups.recon_dart.cooldown-ms", 1500);
        if (!state.canShoot(cd)) return false;

        double range = plugin.getConfig().getDouble("powerups.recon_dart.max-range", 60);
        Location eye = shooter.getEyeLocation();

        Sfx.play(plugin, eye, "recon_dart.fire", Sound.ENTITY_ARROW_SHOOT, 1f, 1.6f);

        RayTraceResult hit = shooter.getWorld().rayTraceEntities(
                eye, eye.getDirection(), range, 0.5,
                e -> e instanceof Player p && !p.equals(shooter) && !p.isDead());

        // Tracer
        if (hit != null && hit.getHitPosition() != null) {
            double len = hit.getHitPosition().distance(eye.toVector());
            for (double d = 0; d < len; d += 0.5) {
                shooter.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        eye.clone().add(eye.getDirection().clone().multiply(d)), 1, 0, 0, 0, 0);
            }
        }

        if (hit == null || !(hit.getHitEntity() instanceof Player target)) {
            shooter.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.GRAY + "Recon dart miste!"));
            return false; // don't waste a use on a miss
        }

        state.markShot();

        int durationSec = plugin.getConfig().getInt("powerups.recon_dart.tracking-duration-seconds", 8);
        PotionEffectType glow = effect("glowing");
        if (glow != null) target.addPotionEffect(new PotionEffect(glow, durationSec * 20, 0, true, false, true));

        Sfx.play(plugin, target.getLocation(), "recon_dart.lock", Sound.BLOCK_BEACON_ACTIVATE, 1f, 2.0f);
        target.getWorld().spawnParticle(Particle.GLOW, target.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0);
        shooter.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.YELLOW + "✛ " + target.getName() + " gemarkeerd!"));
        return true;
    }

    private static PotionEffectType effect(String key) {
        try { return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.MOB_EFFECT).get(NamespacedKey.minecraft(key)); }
        catch (Exception e) { return null; }
    }
}
