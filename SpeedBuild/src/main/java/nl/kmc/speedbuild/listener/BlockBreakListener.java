package nl.kmc.speedbuild.listener;

import nl.kmc.speedbuild.SpeedBuildPlugin;
import nl.kmc.speedbuild.game.SpeedBuildManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/** Anti-exploit: players may only break blocks inside their own active build slot
 *  (protects the blueprint reference and neighbouring slots). */
public final class BlockBreakListener implements Listener {

    private final SpeedBuildPlugin plugin;

    public BlockBreakListener(SpeedBuildPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        SpeedBuildManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;
        Player p = e.getPlayer();
        if (!gm.hasActiveSession(p)) return;
        if (!gm.isInsideBuildArea(p, e.getBlock().getLocation())) {
            e.setCancelled(true);
            p.sendActionBar(net.kyori.adventure.text.Component.text("§cJe kunt hier niets slopen!"));
        }
    }
}
