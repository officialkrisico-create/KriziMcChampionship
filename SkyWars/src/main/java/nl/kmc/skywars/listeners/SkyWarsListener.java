package nl.kmc.skywars.listeners;

import nl.kmc.skywars.SkyWarsPlugin;
import nl.kmc.skywars.managers.SkyWarsGameManagerV2;
import nl.kmc.skywars.models.PlayerStats;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * SkyWars listeners.
 *
 * <ul>
 *   <li>Damage during GRACE: cancelled (chest opening, no PvP)</li>
 *   <li>Damage during ACTIVE: passes through, attribution recorded</li>
 *   <li>Friendly fire: cancelled regardless of phase</li>
 *   <li>Lethal damage: intercepted → routed to SkyWarsGameManagerV2.handleDeath</li>
 *   <li>Death event: cleared, no respawn (player goes spectator)</li>
 * </ul>
 */
public class SkyWarsListener implements Listener {

    private final SkyWarsPlugin plugin;

    public SkyWarsListener(SkyWarsPlugin plugin) { this.plugin = plugin; }

    private SkyWarsGameManagerV2 gm() { return plugin.getSkyWarsGameManagerV2(); }

    /**
     * Block ALL damage to participants during GRACE phase (chest-opening,
     * no PvP allowed). Also block environmental damage to spectators.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        SkyWarsGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerStats ps = gm.getStatsMap().get(p.getUniqueId());
        if (ps == null) return;

        // Spectators: never take damage
        if (p.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }

        // During PREPARING / COUNTDOWN / GRACE: no damage at all
        if (!gm.isPvpAllowed()) {
            event.setCancelled(true);
        }
    }

    /**
     * PvP damage during ACTIVE phase. Records attacker, blocks friendly
     * fire, intercepts lethal damage to drive our own death flow.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        SkyWarsGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        PlayerStats victimStats = gm.getStatsMap().get(victim.getUniqueId());
        if (victimStats == null) return;
        if (victim.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }

        Player attacker = null;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null) return;

        // No PvP yet during grace
        if (!gm.isPvpAllowed()) {
            event.setCancelled(true);
            return;
        }

        // Friendly fire check
        PlayerStats attackerStats = gm.getStatsMap().get(attacker.getUniqueId());
        if (attackerStats != null
                && attackerStats.getTeamId().equals(victimStats.getTeamId())) {
            event.setCancelled(true);
            return;
        }

        gm.recordAttack(victim.getUniqueId(), attacker.getUniqueId());

        // If this would kill, intercept and route through GameManager
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            gm.handleDeath(victim, attacker, "killed");
        }
    }

    /**
     * Defensive — if a player dies despite our intercepts (e.g. fall
     * damage, explosion), funnel through GameManager and prevent
     * vanilla respawn flow.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        SkyWarsGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        Player p = event.getEntity();
        if (gm.getStatsMap().get(p.getUniqueId()) == null) return;

        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);

        Player killer = gm.getRecentAttacker(p.getUniqueId());
        gm.handleDeath(p, killer, "stierf");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        SkyWarsGameManagerV2 gm = gm(); if (gm == null || !gm.getState().isRunning()) return;
        if (gm.getStatsMap().get(event.getPlayer().getUniqueId()) == null) return;
        if (event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    /**
     * TNT auto-prime: when a player places TNT during an active game,
     * the block is replaced with a primed TNT entity instead of sitting
     * inert. Means players don't need flint and steel to fight with TNT.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        SkyWarsGameManagerV2 gm = gm(); if (gm == null || !gm.isPvpAllowed()) return;
        if (event.getBlock().getType() != Material.TNT) return;

        Player placer = event.getPlayer();
        if (gm.getStatsMap().get(placer.getUniqueId()) == null) return;
        if (placer.getGameMode() == GameMode.SPECTATOR) return;

        // Cancel the placement, spawn a primed TNT entity at that location,
        // attribute it to the placer (so kill credit works correctly).
        event.setCancelled(true);

        Block block = event.getBlock();
        // Consume one TNT from the player's hand
        ItemStack hand = event.getItemInHand();
        if (hand != null && hand.getType() == Material.TNT) {
            int amt = hand.getAmount();
            if (amt > 1) hand.setAmount(amt - 1);
            else placer.getInventory().setItem(event.getHand(), null);
        }

        TNTPrimed tnt = block.getWorld().spawn(
                block.getLocation().add(0.5, 0, 0.5), TNTPrimed.class);
        tnt.setSource(placer);
        tnt.setFuseTicks(80);  // 4 seconds — vanilla default

        block.getWorld().playSound(block.getLocation(),
                Sound.ENTITY_TNT_PRIMED, 1f, 1f);
    }
}
