package nl.kmc.bingo.listeners;

import nl.kmc.bingo.BingoPlugin;
import nl.kmc.bingo.managers.BingoGameManagerV2;
import nl.kmc.bingo.util.CardGUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.util.UUID;

/**
 * Recounts a team's bingo progress whenever items move in/out of any
 * member's inventory.
 *
 * <p>Recounts are scheduled on the next tick so that ItemStack.amount
 * has been updated by the time we tally it.
 *
 * <p>Also blocks clicks inside the bingo card GUI (it's read-only).
 */
public class InventoryListener implements Listener {

    private final BingoPlugin plugin;

    public InventoryListener(BingoPlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private BingoGameManagerV2 v2() { return plugin.getBingoManagerV2(); }

    private boolean isGameActive() {
        BingoGameManagerV2 mgr = v2();
        return mgr != null && mgr.isRunning();
    }

    // ----------------------------------------------------------------
    // Events that change inventory contents
    // ----------------------------------------------------------------

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!isGameActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        scheduleRecount(p);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!isGameActive()) return;
        scheduleRecount(event.getPlayer());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // GUI protection
        if (event.getInventory().getHolder() instanceof CardGUI.BingoHolder) {
            event.setCancelled(true);
            return;
        }

        if (!isGameActive()) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;
        scheduleRecount(p);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!isGameActive()) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        scheduleRecount(p);
    }

    private void scheduleRecount(Player p) {
        var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (team == null) return;
        // Defer one tick so amounts have settled
        UUID triggererId = p.getUniqueId();
        BingoGameManagerV2 mgr = v2();
        if (mgr == null) return;
        Bukkit.getScheduler().runTask(plugin,
                () -> mgr.recountTeamInventory(team.getId(), triggererId));
    }
}
