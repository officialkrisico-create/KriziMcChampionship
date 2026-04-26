package nl.kmc.tgttos.listeners;

import nl.kmc.tgttos.TGTTOSPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Movement detection: PlayerMoveEvent → GameManager.handleMovement
 * for finish-region check. Damage cancelled (death only via void
 * fall, handled by GameManager's tick).
 */
public class MovementListener implements Listener {

    private final TGTTOSPlugin plugin;

    public MovementListener(TGTTOSPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getGameManager().isRoundActive()) return;
        if (event.getTo() == null) return;
        // Skip same-block moves
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player p = event.getPlayer();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        plugin.getGameManager().handleMovement(p, event.getTo());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        // Cancel all damage — only the void check eliminates
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }
}
