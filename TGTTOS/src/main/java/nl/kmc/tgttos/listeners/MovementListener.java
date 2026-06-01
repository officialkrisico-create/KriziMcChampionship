package nl.kmc.tgttos.listeners;

import nl.kmc.tgttos.TGTTOSPlugin;
import nl.kmc.tgttos.managers.TGTTOSGameManagerV2;
import nl.kmc.tgttos.models.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Movement detection: PlayerMoveEvent → TGTTOSGameManagerV2.onPlayerReachFinish
 * for finish-region check. Damage cancelled (death only via void
 * fall, handled by GameManager's tick).
 */
public class MovementListener implements Listener {

    private final TGTTOSPlugin plugin;

    public MovementListener(TGTTOSPlugin plugin) { this.plugin = plugin; }

    private TGTTOSGameManagerV2 gm() { return plugin.getTGTTOSGameManagerV2(); }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        TGTTOSGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (event.getTo() == null) return;
        // Skip same-block moves
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player p = event.getPlayer();
        if (gm.getRunnersMap().get(p.getUniqueId()) == null) return;
        // Check if player entered the current map's finish region
        var map = gm.getCurrentMap();
        if (map != null && map.isInFinishRegion(event.getTo())) {
            gm.onPlayerReachFinish(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        TGTTOSGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (gm.getRunnersMap().get(p.getUniqueId()) == null) return;
        // Cancel all damage — only the void check eliminates
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        TGTTOSGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (gm.getRunnersMap().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        TGTTOSGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (gm.getRunnersMap().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }
}
