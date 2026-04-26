package nl.kmc.skywars.listeners;

import nl.kmc.skywars.SkyWarsPlugin;
import nl.kmc.skywars.models.PlayerStats;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * SkyWars listeners.
 *
 * <ul>
 *   <li>Damage during GRACE: cancelled (chest opening, no PvP)</li>
 *   <li>Damage during ACTIVE: passes through, attribution recorded</li>
 *   <li>Friendly fire: cancelled regardless of phase</li>
 *   <li>Lethal damage: intercepted → routed to GameManager.handleDeath</li>
 *   <li>Death event: cleared, no respawn (player goes spectator)</li>
 * </ul>
 */
public class SkyWarsListener implements Listener {

    private final SkyWarsPlugin plugin;

    public SkyWarsListener(SkyWarsPlugin plugin) { this.plugin = plugin; }

    /**
     * Block ALL damage to participants during GRACE phase (chest-opening,
     * no PvP allowed). Also block environmental damage to spectators.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isInMatch()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerStats ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) return;

        // Spectators: never take damage
        if (p.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }

        // During PREPARING / COUNTDOWN / GRACE: no damage at all
        if (!plugin.getGameManager().isPvpAllowed()) {
            event.setCancelled(true);
        }
    }

    /**
     * PvP damage during ACTIVE phase. Records attacker, blocks friendly
     * fire, intercepts lethal damage to drive our own death flow.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!plugin.getGameManager().isInMatch()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        PlayerStats victimStats = plugin.getGameManager().get(victim.getUniqueId());
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
        if (!plugin.getGameManager().isPvpAllowed()) {
            event.setCancelled(true);
            return;
        }

        // Friendly fire check
        PlayerStats attackerStats = plugin.getGameManager().get(attacker.getUniqueId());
        if (attackerStats != null
                && attackerStats.getTeamId().equals(victimStats.getTeamId())) {
            event.setCancelled(true);
            return;
        }

        plugin.getGameManager().recordAttack(victim.getUniqueId(), attacker.getUniqueId());

        // If this would kill, intercept and route through GameManager
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            plugin.getGameManager().handleDeath(victim, attacker, "killed");
        }
    }

    /**
     * Defensive — if a player dies despite our intercepts (e.g. fall
     * damage, explosion), funnel through GameManager and prevent
     * vanilla respawn flow.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getGameManager().isInMatch()) return;
        Player p = event.getEntity();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;

        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);

        Player killer = plugin.getGameManager().getRecentAttacker(p.getUniqueId());
        plugin.getGameManager().handleDeath(p, killer, "stierf");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        // Allow item drops during the match (it's normal SkyWars play),
        // but cancel for spectators
        if (!plugin.getGameManager().isInMatch()) return;
        if (plugin.getGameManager().get(event.getPlayer().getUniqueId()) == null) return;
        if (event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }
}
