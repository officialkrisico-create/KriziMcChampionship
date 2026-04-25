package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Tracks player deaths during the tournament for the post-event stats book.
 *
 * <p>Deaths are stored on PlayerData and persisted in the players table.
 * Counts only deaths during the active tournament — soft reset zeroes them.
 */
public class DeathListener implements Listener {

    private final KMCCore plugin;

    public DeathListener(KMCCore plugin) { this.plugin = plugin; }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        // Only track during active tournament
        if (!plugin.getTournamentManager().isActive()) return;

        var player = event.getEntity();
        PlayerData pd = plugin.getPlayerDataManager().getOrCreate(
                player.getUniqueId(), player.getName());
        pd.addDeath();
        plugin.getDatabaseManager().savePlayer(pd);
    }
}
