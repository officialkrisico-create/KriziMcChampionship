package nl.kmc.tgttos.listeners;

import nl.kmc.tgttos.TGTTOSPlugin;
import nl.kmc.tgttos.managers.TGTTOSGameManagerV2;
import nl.kmc.tgttos.models.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Movement detection: PlayerMoveEvent drives finish-region, checkpoint and
 * void-floor checks (the void floor is detected by Y position here, not by
 * EntityDamageEvent — damage is blanket-cancelled below for the arcade feel,
 * so a real VOID damage cause would never fire).
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
        if (gm.getRunnersMap().get(p.getUniqueId()).isCurrentRoundFinished()) return;

        Map map = gm.getCurrentMap();
        if (map == null) return;
        Location to = event.getTo();

        if (map.isInFinishRegion(to)) { gm.onPlayerReachFinish(p); return; }
        if (map.isBelowVoid(to))      { gm.onPlayerFellInVoid(p); return; }

        int cpIndex = map.checkpointIndexAt(to);
        if (cpIndex >= 0) gm.onPlayerReachCheckpoint(p, cpIndex);
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
