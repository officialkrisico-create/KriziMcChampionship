package nl.kmc.blockparty.listeners;

import nl.kmc.blockparty.BlockPartyPlugin;
import nl.kmc.blockparty.managers.BlockPartyGameManagerV2;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Handles reconnect restoration and a void-fall safety net so an alive player
 * who jumps off the edge mid-round isn't lost to the void before the proper
 * elimination check — they're nudged back onto the floor.
 */
public final class BlockPartyListener implements Listener {

    private final BlockPartyPlugin plugin;

    public BlockPartyListener(BlockPartyPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        BlockPartyGameManagerV2 gm = plugin.getGameManager();
        if (gm != null && gm.isRunning()) gm.handleReconnect(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        BlockPartyGameManagerV2 gm = plugin.getGameManager();
        if (gm == null || !gm.isRunning()) return;
        var arena = plugin.getArenaManager();
        if (arena.getWorld() == null) return;

        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        var bp = gm.getPlayers().get(p.getUniqueId());
        if (bp == null || !bp.isAlive()) return;

        if (p.getLocation().getY() < arena.getVoidY()) {
            // Floor still intact (no elimination yet) — rescue back onto it.
            p.teleport(plugin.floorGen().randomFloorLocation());
        }
    }
}
