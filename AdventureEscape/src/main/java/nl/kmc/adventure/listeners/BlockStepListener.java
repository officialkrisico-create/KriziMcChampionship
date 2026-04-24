package nl.kmc.adventure.listeners;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.EffectBlock;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for player movement and detects when they step on an
 * effect block. Applies the effect, then cools down for 1 second
 * per block-material to avoid re-triggering on the same step.
 */
public class BlockStepListener implements Listener {

    private final AdventureEscapePlugin plugin;

    /** Cooldown tracker: UUID+material → expiry millis. */
    private final Map<String, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 500; // 0.5s per block type per player

    public BlockStepListener(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only during active race
        if (!plugin.getRaceManager().isActive()) return;
        if (event.getTo() == null) return;

        // Skip if same block position (saves CPU)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();

        // The block the player is standing ON (one below their feet)
        Block below = event.getTo().clone().subtract(0, 0.1, 0).getBlock();
        Material mat = below.getType();
        if (mat == Material.AIR) return;

        EffectBlock eb = plugin.getEffectBlockManager().getForMaterial(mat);
        if (eb == null) return;

        // Check cooldown
        String key = player.getUniqueId() + ":" + mat.name();
        long now = System.currentTimeMillis();
        Long expire = cooldowns.get(key);
        if (expire != null && now < expire) return;

        cooldowns.put(key, now + COOLDOWN_MS);
        plugin.getEffectBlockManager().apply(player, eb);
    }
}
