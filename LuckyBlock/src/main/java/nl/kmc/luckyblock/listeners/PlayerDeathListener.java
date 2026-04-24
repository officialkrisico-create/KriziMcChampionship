package nl.kmc.luckyblock.listeners;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.entity.Player;

/**
 * Listens for player deaths during Lucky Block and triggers elimination.
 * Also awards coins to the killer via KMCCore.
 */
public class PlayerDeathListener implements Listener {

    private final LuckyBlockPlugin plugin;

    public PlayerDeathListener(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getGameState().isActive()) return;

        Player dead   = event.getEntity();
        Player killer = dead.getKiller();

        // Suppress default death message — GameStateManager broadcasts its own
        event.setDeathMessage(null);
        event.setDropItems(false);
        event.setKeepInventory(false);

        // Award kill points (reads from KMCCore points.yml — no hardcoded value)
        if (killer != null && !killer.equals(dead)) {
            int awarded = plugin.getKmcCore().getPointsManager().awardKill(killer.getUniqueId());
            killer.sendMessage("§6+" + awarded + " punten voor de kill!");
        }

        // Eliminate from game (teleport to waiting room etc.)
        plugin.getGameState().eliminatePlayer(dead.getUniqueId());
    }
}
