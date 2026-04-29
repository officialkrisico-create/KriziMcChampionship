package nl.kmc.bingo.listeners;

import nl.kmc.bingo.BingoPlugin;
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
    // Events that change inventory contents
    // ----------------------------------------------------------------

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (!plugin.getGameManager().getParticipants().contains(p.getUniqueId())) return;
        scheduleRecount(p);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Player p = event.getPlayer();
        if (!plugin.getGameManager().getParticipants().contains(p.getUniqueId())) return;
        scheduleRecount(p);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // GUI protection
        if (event.getInventory().getHolder() instanceof CardGUI.BingoHolder) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!plugin.getGameManager().getParticipants().contains(p.getUniqueId())) return;
        scheduleRecount(p);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!plugin.getGameManager().getParticipants().contains(p.getUniqueId())) return;
        scheduleRecount(p);
    }

    private void scheduleRecount(Player p) {
        var team = plugin.getKmcCore().getTeamManager().getTeamByPlayer(p.getUniqueId());
        if (team == null) return;
        // Defer one tick so amounts have settled
        UUID triggererId = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin,
                () -> plugin.getGameManager().recountTeam(team.getId(), triggererId));
    }
}
