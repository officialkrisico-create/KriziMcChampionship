package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/**
 * Handles join and quit events.
 *
 * <p>FIX (tablist 2-player glitch): previously we called
 * {@code refreshAll()} once 5 ticks after join. With few players
 * online the refresh sometimes fires before Paper has finished
 * sending the join packet, so the new player's prefix briefly
 * flashes correct then resets.
 *
 * <p>The fix is to schedule THREE refreshes at 5, 20, and 40 ticks
 * after join — belt-and-braces approach that always wins the race
 * against Paper's packet flush.
 */
public class PlayerJoinQuitListener implements Listener {

    private final KMCCore plugin;

    public PlayerJoinQuitListener(KMCCore plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();

        PlayerData pd = plugin.getPlayerDataManager()
                .getOrCreate(player.getUniqueId(), player.getName());

        plugin.getScoreboardManager().onPlayerJoin(player);
        plugin.getAutomationManager().addPlayerToBossBar(player);
        plugin.getTabListManager().updateTabList(player);

        // MULTIPLE delayed refreshes to beat Paper's packet timing
        // with low player counts — ensures all prefixes propagate
        schedule(5L);
        schedule(20L);
        schedule(40L);

        // Lobby teleport in adventure mode
        if (plugin.getArenaManager().getLobby() != null
                && !plugin.getGameManager().isGameActive()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                player.teleport(plugin.getArenaManager().getLobby());
                player.setGameMode(GameMode.ADVENTURE);
            }, 10L);
        }
    }

    /** Schedules a full nametag/tablist refresh after {@code ticks}. */
    private void schedule(long ticks) {
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getTabListManager().refreshAll(), ticks);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        plugin.getPlayerDataManager().unload(player.getUniqueId());
        plugin.getScoreboardManager().onPlayerQuit(player);
    }
}
