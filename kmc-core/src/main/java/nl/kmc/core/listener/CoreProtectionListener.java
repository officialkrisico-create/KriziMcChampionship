package nl.kmc.core.listener;

import nl.kmc.core.service.TournamentService;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lobby protection when no game is active. Prevents block modification
 * and PvP during ceremonies and intermission.
 */
public final class CoreProtectionListener implements Listener {

    private final JavaPlugin        plugin;
    private final TournamentService tournament;

    public CoreProtectionListener(JavaPlugin plugin, TournamentService tournament) {
        this.plugin     = plugin;
        this.tournament = tournament;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!tournament.getPhase().isGameRunning()
                && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!tournament.getPhase().isGameRunning()
                && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!tournament.getPhase().isGameRunning()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (!tournament.getPhase().isGameRunning()) {
            e.setCancelled(true);
        }
    }
}
