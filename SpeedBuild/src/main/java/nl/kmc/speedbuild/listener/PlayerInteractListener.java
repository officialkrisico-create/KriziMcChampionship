package nl.kmc.speedbuild.listener;

import nl.kmc.speedbuild.SpeedBuildPlugin;
import nl.kmc.speedbuild.game.SpeedBuildManager;
import nl.kmc.speedbuild.ui.InventoryButtons;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Routes clicks on the GREEN / RED / GOLD control items to the game manager. */
public final class PlayerInteractListener implements Listener {

    private final SpeedBuildPlugin plugin;

    public PlayerInteractListener(SpeedBuildPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        SpeedBuildManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;

        Player p = e.getPlayer();
        InventoryButtons.Button button = plugin.getButtons().identify(e.getItem());
        if (button == null) return;

        e.setCancelled(true); // never place the control wool
        switch (button) {
            case COMPLETE  -> gm.completeBuild(p);
            case BLUEPRINT -> gm.showBlueprint(p);
            case FINISH    -> gm.finishSession(p);
        }
    }
}
