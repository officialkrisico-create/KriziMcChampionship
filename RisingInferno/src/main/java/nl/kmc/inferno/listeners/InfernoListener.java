package nl.kmc.inferno.listeners;

import nl.kmc.inferno.RisingInfernoPlugin;
import nl.kmc.inferno.managers.RisingInfernoGameManagerV2;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Keeps the climb clean: nothing can hurt a climber except the lava (so no fall damage, no fire damage —
 * the game logic eliminates), no hunger, no item juggling, no block changes. Leaving eliminates.
 */
public final class InfernoListener implements Listener {

    private final RisingInfernoPlugin plugin;

    public InfernoListener(RisingInfernoPlugin plugin) { this.plugin = plugin; }

    private boolean inGame(Player p) {
        RisingInfernoGameManagerV2 gm = plugin.getGameManagerV2();
        return gm != null && gm.getState().isRunning() && gm.isParticipant(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inGame(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        RisingInfernoGameManagerV2 gm = plugin.getGameManagerV2();
        if (gm != null && gm.getState().isRunning()) gm.handleQuit(e.getPlayer().getUniqueId());
    }
}
