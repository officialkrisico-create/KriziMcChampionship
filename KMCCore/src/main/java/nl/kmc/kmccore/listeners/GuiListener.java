package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.gui.Gui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes inventory clicks in any {@link Gui} to that GUI's per-slot actions,
 * and prevents item theft (all clicks are cancelled).
 */
public final class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        // Only act on clicks in the GUI's own (top) inventory.
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory().getHolder() != gui) return;
        gui.handleClick(p, event.getSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }
}
