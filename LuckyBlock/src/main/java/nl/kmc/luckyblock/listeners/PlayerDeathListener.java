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
        event.getDrops().clear();
        event.setKeepInventory(false);

        // Award kill points using Lucky Block's per-game value (20 by default).
        // Goes through KMCApi.givePoints so the round multiplier still applies.
        if (killer != null && !killer.equals(dead)) {
            int killPts = plugin.getConfig().getInt("points.per-kill", 20);
            if (killPts > 0) {
                plugin.getKmcCore().getApi().givePoints(killer.getUniqueId(), killPts);
                killer.sendMessage("§6+" + killPts + " punten voor de kill!");
            }
            // Track the kill in HoF + per-player stats (not the points)
            plugin.getKmcCore().getHallOfFameManager().recordKill(killer);
        }

        // Eliminate from game (teleport to waiting room etc.)
        plugin.getGameState().eliminatePlayer(dead.getUniqueId());
    }
}
