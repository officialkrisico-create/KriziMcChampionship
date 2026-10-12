package nl.kmc.kmccore.lobbygames;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Feeds player movement and clicks to the lobby parkour and the lobby spleef. */
public final class LobbyArcadeListener implements Listener {

    private final LobbyGamesManager mgr;

    public LobbyArcadeListener(LobbyGamesManager mgr) { this.mgr = mgr; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // Only when the player entered a new block: this event fires for every head turn otherwise.
        if (e.getTo() == null || (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ())) return;
        mgr.parkour().onMove(e.getPlayer());
    }

    /** Left-click breaks a floor block instantly for spleef players (protection ignores adventure clicks). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (mgr.spleef().isRunning() && mgr.spleef().tryBreak(e.getPlayer(), e.getClickedBlock()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mgr.parkour().cancel(e.getPlayer().getUniqueId());
        mgr.spleef().onQuit(e.getPlayer());
    }
}
