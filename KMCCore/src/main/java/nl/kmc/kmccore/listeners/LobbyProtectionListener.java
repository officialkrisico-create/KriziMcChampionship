package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Protects the lobby from damage, mob spawning, and griefing.
 *
 * <p>Behaviour inside the lobby "protection radius" (configurable):
 * <ul>
 *   <li>Players take no damage</li>
 *   <li>No mobs can spawn (all CreatureSpawnEvent reasons blocked except CUSTOM)</li>
 *   <li>Players in ADVENTURE or SURVIVAL mode can't place/break blocks</li>
 *   <li>Players can't drop items</li>
 *   <li>Hunger doesn't deplete</li>
 * </ul>
 *
 * <p>Radius is configurable via {@code arena.lobby-protection-radius} (default 100).
 * The check uses squared distance for performance.
 */
public class LobbyProtectionListener implements Listener {

    private final KMCCore plugin;

    public LobbyProtectionListener(KMCCore plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    /**
     * @return true if the location is within the lobby protection radius
     *         AND in the same world as the lobby
     */
    private boolean isInLobbyZone(Location loc) {
        Location lobby = plugin.getArenaManager().getLobby();
        if (lobby == null || loc == null) return false;
        if (!loc.getWorld().equals(lobby.getWorld())) return false;

        double radius = plugin.getConfig().getDouble("arena.lobby-protection-radius", 100.0);
        return loc.distanceSquared(lobby) <= (radius * radius);
    }

    // ----------------------------------------------------------------
    // No damage in lobby
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Skip if player is in CREATIVE mode (op admins can still hurt themselves to test)
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Only protect if in lobby zone AND no active game is running for them
        if (isInLobbyZone(player.getLocation())) {
            // Also skip if game is active — let Lucky Block etc. handle damage
            if (!plugin.getGameManager().isGameActive()) {
                event.setCancelled(true);
            }
        }
    }

    // ----------------------------------------------------------------
    // No hunger in lobby
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isInLobbyZone(player.getLocation())) event.setCancelled(true);
    }

    // ----------------------------------------------------------------
    // No mob spawning in lobby zone
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // Allow CUSTOM spawns (plugins spawning mobs for a reason — e.g. Lucky Block)
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;

        if (isInLobbyZone(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    // ----------------------------------------------------------------
    // No block break / place unless CREATIVE
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (!plugin.getGameManager().isGameActive()
                && isInLobbyZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (!plugin.getGameManager().isGameActive()
                && isInLobbyZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ----------------------------------------------------------------
    // No item dropping in lobby
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (!plugin.getGameManager().isGameActive() && isInLobbyZone(p.getLocation())) {
            event.setCancelled(true);
        }
    }
}
