package nl.kmc.bridge.listeners;

import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.models.BridgeTeam;
import nl.kmc.bridge.models.PlayerStats;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Handles all in-game events.
 *
 * <ul>
 *   <li>Block place: only wool of player's team color, tracked for cleanup.</li>
 *   <li>Block break: only player-placed blocks, never the world.</li>
 *   <li>Movement: route to GameManager for goal check.</li>
 *   <li>PvP damage: pass through (so combat works).</li>
 *   <li>Player death: route to GameManager.handleDeath, cancel vanilla flow.</li>
 *   <li>Drop / swap: cancelled (preserve kit).</li>
 * </ul>
 */
public class BridgeListener implements Listener {

    private final TheBridgePlugin plugin;

    public BridgeListener(TheBridgePlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Block place — only team wool, only in non-goal regions, tracked
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Player p = event.getPlayer();
        PlayerStats ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) {
            event.setCancelled(true);
            return;
        }
        BridgeTeam team = plugin.getArenaManager().getTeam(ps.getTeamId());
        if (team == null) {
            event.setCancelled(true);
            return;
        }

        // Only allow placing the team's wool
        Material placed = event.getBlockPlaced().getType();
        if (placed != team.getWoolMaterial()) {
            event.setCancelled(true);
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    org.bukkit.ChatColor.RED + "Je kunt alleen je team wol plaatsen!"));
            return;
        }

        // Track for cleanup
        plugin.getBlockTracker().recordPlacement(event.getBlockPlaced());
        plugin.getGameManager().onBlockPlaced(p);
    }

    // ----------------------------------------------------------------
    // Block break — only player-placed blocks
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Player p = event.getPlayer();
        PlayerStats ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.getBlockTracker().wasPlaced(event.getBlock())) {
            event.setCancelled(true);
            return;
        }

        // Allow but don't drop wool (kit refills automatically)
        event.setDropItems(false);
        plugin.getBlockTracker().unrecord(event.getBlock());
    }

    // ----------------------------------------------------------------
    // Movement — goal detection
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player p = event.getPlayer();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        plugin.getGameManager().handleMovement(p, event.getTo());
    }

    // ----------------------------------------------------------------
    // Player damage / death — PvP active, but route void/death through
    // GameManager so it controls respawn
    // ----------------------------------------------------------------

    /**
     * Detect lethal damage — if it would kill the player, intercept
     * and route to GameManager.handleDeath so we control respawn
     * instead of vanilla.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (plugin.getGameManager().get(victim.getUniqueId()) == null) return;

        // Find the actual attacker (could be a projectile)
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null) return;

        // Friendly fire check — same team = no damage
        PlayerStats victimStats   = plugin.getGameManager().get(victim.getUniqueId());
        PlayerStats attackerStats = plugin.getGameManager().get(attacker.getUniqueId());
        if (victimStats != null && attackerStats != null
                && victimStats.getTeamId().equals(attackerStats.getTeamId())) {
            event.setCancelled(true);
            return;
        }

        // If the damage would kill, intercept
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            plugin.getGameManager().handleDeath(victim, attacker);
        }
    }

    /** Defensive: if player somehow dies anyway (other source), treat as void death. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Player p = event.getEntity();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);
        plugin.getGameManager().handleDeath(p, null);
    }

    // ----------------------------------------------------------------
    // Inventory protection
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }
}
