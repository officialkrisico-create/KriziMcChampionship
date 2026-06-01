package nl.kmc.bridge.listeners;

import nl.kmc.bridge.TheBridgePlugin;
import nl.kmc.bridge.managers.BridgeGameManagerV2;
import nl.kmc.bridge.models.BridgeTeam;
import nl.kmc.bridge.models.PlayerStats;
import org.bukkit.Location;
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
 *   <li>Movement: route to BridgeGameManagerV2 for goal check.</li>
 *   <li>PvP damage: pass through (so combat works).</li>
 *   <li>Player death: route to BridgeGameManagerV2.handleDeath, cancel vanilla flow.</li>
 *   <li>Drop / swap: cancelled (preserve kit).</li>
 * </ul>
 */
public class BridgeListener implements Listener {

    private final TheBridgePlugin plugin;

    public BridgeListener(TheBridgePlugin plugin) { this.plugin = plugin; }

    private BridgeGameManagerV2 gm() { return plugin.getBridgeGameManagerV2(); }

    // ----------------------------------------------------------------
    // Block place — only team wool, only in non-goal regions, tracked
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        Player p = event.getPlayer();
        PlayerStats ps = gm.getStatsMap().get(p.getUniqueId());
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
    }

    // ----------------------------------------------------------------
    // Block break — only player-placed blocks
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        Player p = event.getPlayer();
        PlayerStats ps = gm.getStatsMap().get(p.getUniqueId());
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
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        Player p = event.getPlayer();
        if (gm.getStatsMap().get(p.getUniqueId()) == null) return;

        // Check if the player entered a goal region
        Location to = event.getTo();
        BridgeTeam playerTeam = getPlayerTeam(gm, p);
        if (playerTeam == null) return;
        BridgeTeam goalTeam = plugin.getArenaManager().findGoalTeam(to);
        if (goalTeam == null) return;
        // Can't score in own goal
        if (goalTeam.getId().equals(playerTeam.getId())) return;

        gm.onGoalScored(p);
    }

    private BridgeTeam getPlayerTeam(BridgeGameManagerV2 gm, Player p) {
        PlayerStats ps = gm.getStatsMap().get(p.getUniqueId());
        if (ps == null) return null;
        return plugin.getArenaManager().getTeam(ps.getTeamId());
    }

    // ----------------------------------------------------------------
    // Player damage / death — PvP active, but route void/death through
    // GameManager so it controls respawn
    // ----------------------------------------------------------------

    /**
     * Detect lethal damage — if it would kill the player, intercept
     * and route to BridgeGameManagerV2.handleDeath so we control respawn
     * instead of vanilla.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (gm.getStatsMap().get(victim.getUniqueId()) == null) return;

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
        PlayerStats victimStats   = gm.getStatsMap().get(victim.getUniqueId());
        PlayerStats attackerStats = gm.getStatsMap().get(attacker.getUniqueId());
        if (victimStats != null && attackerStats != null
                && victimStats.getTeamId().equals(attackerStats.getTeamId())) {
            event.setCancelled(true);
            return;
        }

        // Record the attack for kill credit
        if (attacker != null) gm.recordAttack(victim.getUniqueId(), attacker.getUniqueId());

        // If the damage would kill, intercept
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            gm.handleDeath(victim);
        }
    }

    /** Defensive: if player somehow dies anyway (other source), treat as void death. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        Player p = event.getEntity();
        if (gm.getStatsMap().get(p.getUniqueId()) == null) return;
        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);
        gm.handleDeath(p);
    }

    // ----------------------------------------------------------------
    // Inventory protection
    // ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        if (gm.getStatsMap().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        BridgeGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        if (gm.getStatsMap().get(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
    }
}
