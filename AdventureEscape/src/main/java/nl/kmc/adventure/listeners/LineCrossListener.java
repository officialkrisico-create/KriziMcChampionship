package nl.kmc.adventure.listeners;

import nl.kmc.adventure.AdventureEscapePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects when a player enters the start or finish line region.
 * Ensures lap crossings only count once per second per player
 * to avoid double-triggering.
 */
public class LineCrossListener implements Listener {

    private final AdventureEscapePlugin plugin;

    private final Map<UUID, Long> finishCooldown = new HashMap<>();
    private final Map<UUID, Long> startCooldown  = new HashMap<>();

    public LineCrossListener(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getRaceManager().isActive()) return;
        if (event.getTo() == null) return;

        // Only check when block position changes
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();

        // Finish line
        if (plugin.getArenaManager().isInFinishline(event.getTo())) {
            Long expire = finishCooldown.get(player.getUniqueId());
            if (expire == null || now > expire) {
                finishCooldown.put(player.getUniqueId(), now + 2000); // 2s cooldown
                plugin.getRaceManager().onPlayerCrossFinish(player);
            }
            return;
        }

        // Start line
        if (plugin.getArenaManager().isInStartline(event.getTo())) {
            Long expire = startCooldown.get(player.getUniqueId());
            if (expire == null || now > expire) {
                startCooldown.put(player.getUniqueId(), now + 2000);
                plugin.getRaceManager().onPlayerCrossStart(player);
            }
        }
    }
}
