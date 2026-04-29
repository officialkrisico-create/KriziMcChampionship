package nl.kmc.spleef.listeners;

import nl.kmc.spleef.SpleefPlugin;
import nl.kmc.spleef.models.PlayerState;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.Vector;

/**
 * Spleef event handling.
 *
 * <ul>
 *   <li>Block break: only floor blocks can be broken, only by living
 *       participants. Other breaks cancelled.</li>
 *   <li>Block place: cancelled (no resealing the floor).</li>
 *   <li>Item drop / off-hand swap: cancelled (don't lose your shovel).</li>
 *   <li>Snowball pickup: routes to PowerupSpawner for tracking.</li>
 *   <li>Snowball hit: knocks the target around.</li>
 *   <li>Damage: cancel everything except void — snowballs shouldn't
 *       hurt, only knock back.</li>
 * </ul>
 */
public class SpleefListener implements Listener {

    private final SpleefPlugin plugin;

    public SpleefListener(SpleefPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Block break — only floor blocks, only by alive participants
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Player p = event.getPlayer();

        PlayerState ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) {
            // Non-participant — no breaking during a game
            event.setCancelled(true);
            return;
        }
        if (!ps.isAlive()) {
            event.setCancelled(true);
            return;
        }

        // Only floor blocks, only with shovel
        if (!plugin.getFloorManager().isFloorBlock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        var held = p.getInventory().getItemInMainHand();
        if (held == null || !held.getType().name().endsWith("_SHOVEL")) {
            event.setCancelled(true);
            return;
        }

        // Allow the break — clear the block ourselves so we don't drop
        // a snow block item, then untrack it
        event.setDropItems(false);
        plugin.getFloorManager().unregisterBlock(event.getBlock());
        plugin.getGameManager().onFloorBlockBroken(p);

        // Track this break for kill-credit attribution
        plugin.getGameManager().recordBlockBreakNearby(p, event.getBlock());

        // Subtle visual at the broken block
        event.getBlock().getWorld().spawnParticle(Particle.SNOWFLAKE,
                event.getBlock().getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0.02);
    }

    // ----------------------------------------------------------------
    // Block place — never allowed
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    // ----------------------------------------------------------------
    // Don't lose your shovel
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        Material type = event.getItemDrop().getItemStack().getType();
        if (type.name().endsWith("_SHOVEL")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    // ----------------------------------------------------------------
    // Snowball powerup pickup
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerState ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null || !ps.isAlive()) {
            event.setCancelled(true);
            return;
        }

        Item item = event.getItem();
        if (plugin.getPowerupSpawner().isPowerupItem(item.getUniqueId())) {
            plugin.getPowerupSpawner().unregisterPickedUp(item.getUniqueId());
            // Let the pickup proceed — player gets the snowballs in inventory
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.8f);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.AQUA + "❄ Snowballs! Right-click to throw"));
        }
    }

    // ----------------------------------------------------------------
    // Snowball impact — boost knockback
    // ----------------------------------------------------------------

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Projectile proj = event.getEntity();
        if (!(proj instanceof Snowball)) return;
        if (!(event.getHitEntity() instanceof Player target)) return;
        PlayerState ps = plugin.getGameManager().get(target.getUniqueId());
        if (ps == null || !ps.isAlive()) return;

        // Boosted knockback in the projectile's direction
        double power = plugin.getConfig().getDouble("powerups.snowball-knockback", 1.5);
        Vector dir = proj.getVelocity().normalize().multiply(power);
        // Add a bit of upward component so they go off the floor
        dir.setY(0.4);
        target.setVelocity(target.getVelocity().add(dir));
        target.getWorld().spawnParticle(Particle.SNOWFLAKE,
                target.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.05);
        target.getWorld().playSound(target.getLocation(),
                Sound.BLOCK_SNOW_BREAK, 1f, 1.5f);
    }

    // ----------------------------------------------------------------
    // Damage — block all environmental damage; void check handles falls
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerState ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) return;
        // Block ALL damage — players are eliminated only by void fall
        event.setCancelled(true);
    }
}
