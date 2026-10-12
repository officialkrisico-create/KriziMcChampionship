package nl.kmc.hitw.listeners;

import nl.kmc.hitw.HoleInTheWallPlugin;
import nl.kmc.hitw.managers.HoleInTheWallGameManagerV2;
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
 * Keeps a match clean: no damage of any kind (walls eliminate through the game logic, not through
 * health), no hunger, no item juggling, no block changes — and a participant who leaves is eliminated.
 */
public class HitwListener implements Listener {

    private final HoleInTheWallPlugin plugin;

    public HitwListener(HoleInTheWallPlugin plugin) { this.plugin = plugin; }

    private HoleInTheWallGameManagerV2 gm() { return plugin.getGameManagerV2(); }

    private boolean inMatch(Player p) {
        var gm = gm();
        return gm != null && gm.getState().isRunning() && gm.isParticipant(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inMatch(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inMatch(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (inMatch(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (inMatch(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (inMatch(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (inMatch(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        var gm = gm();
        if (gm != null && gm.getState().isRunning()) gm.handleQuit(e.getPlayer().getUniqueId());
    }
}
