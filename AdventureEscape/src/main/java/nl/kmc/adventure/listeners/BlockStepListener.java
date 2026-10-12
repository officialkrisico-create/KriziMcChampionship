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

    /**
     * Generous effect-block check, called from the race poll: looks at the blocks under the player's whole
     * footprint (centre and corners), from 2 blocks below up to the knees — so brushing a corner, or flying
     * over a block in a jump, still triggers it.
     */
    public void check(Player player) {
        if (!plugin.getRaceManager().isActive()) return;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        var rd = plugin.getRaceManager().getRacers().get(player.getUniqueId());
        if (rd == null || rd.hasFinished()) return;

        org.bukkit.Location l = player.getLocation();
        var world = l.getWorld();
        if (world == null) return;
        double[][] offs = {{0, 0}, {0.35, 0.35}, {0.35, -0.35}, {-0.35, 0.35}, {-0.35, -0.35}};
        int footY = (int) Math.floor(l.getY() - 0.05);
        for (double[] o : offs) {
            int bx = (int) Math.floor(l.getX() + o[0]);
            int bz = (int) Math.floor(l.getZ() + o[1]);
            for (int dy = 0; dy <= 2; dy++) {
                Material mat = world.getBlockAt(bx, footY - dy, bz).getType();
                if (mat == Material.AIR) continue;
                EffectBlock eb = plugin.getEffectBlockManager().getForMaterial(mat);
                if (eb == null) continue;
                String key = player.getUniqueId() + ":" + mat.name();
                long now = System.currentTimeMillis();
                Long expire = cooldowns.get(key);
                if (expire != null && now < expire) continue;
                cooldowns.put(key, now + COOLDOWN_MS);
                plugin.getEffectBlockManager().apply(player, eb);
                return;   // one effect per poll is plenty
            }
        }
    }

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
