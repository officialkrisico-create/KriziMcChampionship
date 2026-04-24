package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Listens for player deaths to award kill points to the killer.
 */
public class PlayerKillListener implements Listener {

    private final KMCCore plugin;

    public PlayerKillListener(KMCCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Only count kills during an active tournament game
        if (!plugin.getTournamentManager().isActive()) return;
        if (!plugin.getGameManager().isGameActive())   return;

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (killer.equals(event.getEntity())) return; // self-kill

        plugin.getPointsManager().awardKill(killer.getUniqueId());
    }
}
