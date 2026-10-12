package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.GameCameraGui;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** The camera wand handed out by {@link GameCameraGui}: right-click adds a waypoint, shift+right-click opens the menu. */
public final class CameraWandListener implements Listener {

    private final KMCCore plugin;
    private final NamespacedKey key;

    public CameraWandListener(KMCCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, GameCameraGui.WAND_KEY);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String data = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (data == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.isSneaking()) {
            String[] parts = data.split("\\|", 2);
            String gameId = parts[0];
            String name   = parts.length > 1 ? parts[1] : parts[0];
            new GameCameraGui(plugin, gameId, name, p).open(p);
        } else {
            GameCameraGui.addPoint(plugin, p);
        }
    }
}
