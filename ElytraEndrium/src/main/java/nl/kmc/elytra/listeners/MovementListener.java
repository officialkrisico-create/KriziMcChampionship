package nl.kmc.elytra.listeners;

import nl.kmc.elytra.ElytraEndriumPlugin;
import nl.kmc.elytra.models.BoostHoop;
import nl.kmc.elytra.models.Checkpoint;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Movement-driven detection: checkpoints, boost hoops, prevent
 * unwanted damage, prevent dropping/swapping the elytra.
 */
public class MovementListener implements Listener {

    private final ElytraEndriumPlugin plugin;

    public MovementListener(ElytraEndriumPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (event.getTo() == null) return;

        // Skip same-block moves to keep this cheap
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player p = event.getPlayer();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;

        // Checkpoint check
        Checkpoint cp = plugin.getCourseManager().findCheckpointAt(event.getTo());
        if (cp != null) {
            plugin.getGameManager().handleCheckpointEntry(p, cp);
            return;
        }

        // Boost hoop check
        BoostHoop boost = plugin.getCourseManager().findBoostAt(event.getTo());
        if (boost != null) {
            plugin.getGameManager().handleBoostEntry(p, boost);
        }
    }

    /**
     * Cancel all damage to participants — they shouldn't lose HP
     * during a race. Crashes are detected separately via the
     * grounded-grace-period check in GameManager.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    /**
     * Don't let the player toggle gliding off (they need to keep
     * gliding for the whole race).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        // If they're trying to STOP gliding mid-air, cancel it
        if (!event.isGliding() && !p.isOnGround()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        if (event.getItemDrop().getItemStack().getType() == Material.ELYTRA) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }
}
