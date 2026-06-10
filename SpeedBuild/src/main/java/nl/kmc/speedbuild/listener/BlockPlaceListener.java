package nl.kmc.speedbuild.listener;

import nl.kmc.speedbuild.SpeedBuildPlugin;
import nl.kmc.speedbuild.game.SpeedBuildManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** Anti-exploit: players may only place blocks inside their own active build slot. */
public final class BlockPlaceListener implements Listener {

    private final SpeedBuildPlugin plugin;

    public BlockPlaceListener(SpeedBuildPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        SpeedBuildManager gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;
        Player p = e.getPlayer();
        if (!gm.hasActiveSession(p)) return;
        if (!gm.isInsideBuildArea(p, e.getBlock().getLocation())) {
            e.setCancelled(true);
            p.sendActionBar(net.kyori.adventure.text.Component.text("§cBouw alleen in je eigen bouwgebied!"));
        }
    }
}
