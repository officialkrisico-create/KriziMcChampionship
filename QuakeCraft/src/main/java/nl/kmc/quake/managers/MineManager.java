package nl.kmc.quake.managers;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Tracks placed proximity mines and detonates them when an enemy walks close.
 *
 * <p>Started/stopped with the game (alongside the powerup spawner). Each mine
 * has an arming delay so the placer isn't instantly blown up, a subtle particle
 * marker, and a blast that credits kills to the owner via the game manager.
 */
public final class MineManager {

    private record Mine(Location loc, UUID owner, long armedAtMs) {}

    private final QuakeCraftPlugin plugin;
    private final List<Mine> mines = new ArrayList<>();
    private BukkitTask task;

    public MineManager(QuakeCraftPlugin plugin) { this.plugin = plugin; }

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        mines.clear();
    }

    /** Places a mine at the player's feet, owned by them. */
    public void place(Player owner) {
        long armMs = plugin.getConfig().getLong("powerups.proximity_mine.arm-delay-ms", 1000);
        Location loc = owner.getLocation().clone();
        mines.add(new Mine(loc, owner.getUniqueId(), System.currentTimeMillis() + armMs));
        owner.getWorld().playSound(loc, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 0.8f);
        owner.sendActionBar(net.kyori.adventure.text.Component.text(
                org.bukkit.ChatColor.RED + "✖ Mijn geplaatst!"));
    }

    private void tick() {
        if (mines.isEmpty()) return;

        double triggerR = plugin.getConfig().getDouble("powerups.proximity_mine.trigger-radius", 2.5);
        double blastR   = plugin.getConfig().getDouble("powerups.proximity_mine.explosion-radius", 4.0);
        double triggerSq = triggerR * triggerR;
        long now = System.currentTimeMillis();

        Iterator<Mine> it = mines.iterator();
        while (it.hasNext()) {
            Mine mine = it.next();
            if (mine.loc().getWorld() == null) { it.remove(); continue; }

            // Marker particle (red dust-ish)
            mine.loc().getWorld().spawnParticle(Particle.SMALL_FLAME,
                    mine.loc().clone().add(0, 0.1, 0), 1, 0.1, 0.02, 0.1, 0);

            if (now < mine.armedAtMs()) continue; // still arming

            Player trigger = null;
            for (var e : mine.loc().getWorld().getNearbyEntities(mine.loc(), triggerR, triggerR, triggerR)) {
                if (!(e instanceof Player pl) || pl.isDead()) continue;
                if (pl.getUniqueId().equals(mine.owner())) continue; // owner doesn't trip it
                if (pl.getLocation().distanceSquared(mine.loc()) <= triggerSq) { trigger = pl; break; }
            }

            if (trigger != null) {
                detonate(mine, blastR);
                it.remove();
            }
        }
    }

    private void detonate(Mine mine, double radius) {
        Location loc = mine.loc();
        if (loc.getWorld() == null) return;

        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 1);
        loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 10, 0.6, 0.4, 0.6, 0);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.1f);

        Player owner = plugin.getServer().getPlayer(mine.owner());
        double radiusSq = radius * radius;
        for (var entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!(entity instanceof Player target) || target.isDead()) continue;
            if (target.getUniqueId().equals(mine.owner())) continue;
            if (target.getLocation().distanceSquared(loc) > radiusSq) continue;

            // Line-of-sight gate, same as the grenade/bazooka.
            var toTarget = target.getEyeLocation().toVector().subtract(loc.toVector());
            if (loc.getWorld().rayTraceBlocks(loc, toTarget, toTarget.length()) != null) continue;

            if (owner != null && plugin.getGameManagerV2() != null) {
                plugin.getGameManagerV2().handleHit(owner, target, "proximity_mine");
            }
        }
    }
}
