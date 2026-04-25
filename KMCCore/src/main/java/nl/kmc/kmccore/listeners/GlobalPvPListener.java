package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Global PvP disabler for the KMC tournament.
 *
 * <p>Cancels ALL player-vs-player damage by default. Specific minigames
 * that want PvP (like Survival Games or The Bridge) can un-cancel the
 * event in their own listener at {@link EventPriority#HIGHEST}.
 *
 * <p>This runs at NORMAL priority so minigames can override at HIGHEST.
 */
public class GlobalPvPListener implements Listener {

    private final KMCCore plugin;

    public GlobalPvPListener(KMCCore plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity()  instanceof Player)) return;

        // Cancel by default — minigames that need PvP will un-cancel at HIGHEST
        event.setCancelled(true);
    }
}
