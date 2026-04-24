package nl.kmc.luckyblock.listeners;

import nl.kmc.luckyblock.LuckyBlockPlugin;
import nl.kmc.luckyblock.util.LootExecutor;
import nl.kmc.luckyblock.models.LootEntry;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.entity.Player;

/**
 * Triggers loot when a tracked yellow concrete block is broken during an active game.
 */
public class BlockBreakListener implements Listener {

    private final LuckyBlockPlugin plugin;
    private final LootExecutor     lootExecutor;

    public BlockBreakListener(LuckyBlockPlugin plugin) {
        this.plugin       = plugin;
        this.lootExecutor = new LootExecutor(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getGameState().isActive()) return;
        if (!plugin.getGameState().isAlive(player.getUniqueId())) return;
        if (!plugin.getTracker().isLuckyBlock(event.getBlock().getLocation())) return;

        event.setDropItems(false);
        plugin.getTracker().onBlockBroken(event.getBlock().getLocation());

        LootEntry loot = plugin.getLootTable().pick();
        if (loot != null) {
            lootExecutor.execute(loot, player, event.getBlock().getLocation());
        }
    }
}
