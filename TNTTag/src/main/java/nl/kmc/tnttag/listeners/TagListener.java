package nl.kmc.tnttag.listeners;

import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.models.PlayerState;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * TNT Tag listeners — keep the game state consistent.
 *
 * <ul>
 *   <li>Cancel all damage — only round detonation eliminates.</li>
 *   <li>Cancel block place/break — players shouldn't modify the arena.</li>
 *   <li>Cancel item drop / hand swap / inventory click on TNT helmet.</li>
 * </ul>
 */
public class TagListener implements Listener {

    private final TNTTagPlugin plugin;

    public TagListener(TNTTagPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        // Cancel ALL damage — eliminations only via void check or round detonation
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    /**
     * Don't let "it" players take their TNT helmet off via inventory.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;
        PlayerState ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) return;
        // Only allow inventory interactions if not in player inventory
        if (event.getView().getType() == InventoryType.CRAFTING) {
            // Block clicks on the armor slots specifically, and on TNT items
            if (event.getCurrentItem() != null
                    && event.getCurrentItem().getType() == Material.TNT) {
                event.setCancelled(true);
                return;
            }
            // Block clicks on slot 5 (helmet armor slot)
            if (event.getSlot() == 39) {
                event.setCancelled(true);
            }
        }
    }
}
