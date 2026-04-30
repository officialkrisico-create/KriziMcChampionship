package nl.kmc.sg.listeners;

import nl.kmc.sg.SurvivalGamesPlugin;
import nl.kmc.sg.models.PlayerStats;
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
import org.bukkit.inventory.ItemStack;

/**
 * Survival Games listeners.
 *
 * <ul>
 *   <li>Damage during COUNTDOWN: cancelled (players frozen on pedestals)</li>
 *   <li>Damage during GRACE/ACTIVE/DEATHMATCH: passes through, attribution recorded</li>
 *   <li>Lethal damage: intercepted → routed to GameManager.handleDeath</li>
 *   <li>Death event: cleared, no respawn</li>
 * </ul>
 *
 * <p>Solo mode — no friendly fire check. Anyone vs anyone.
 */
public class SGListener implements Listener {

    private final SurvivalGamesPlugin plugin;

    public SGListener(SurvivalGamesPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isInMatch()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerStats ps = plugin.getGameManager().get(p.getUniqueId());
        if (ps == null) return;

        if (p.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }

        // No damage during PREPARING / COUNTDOWN
        if (!plugin.getGameManager().isPvpAllowed()) {
            event.setCancelled(true);
        }
    }

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

        if (!plugin.getGameManager().isPvpAllowed()) {
            event.setCancelled(true);
            return;
        }

        plugin.getGameManager().recordAttack(victim.getUniqueId(), attacker.getUniqueId());

        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            plugin.getGameManager().handleDeath(victim, attacker, "killed");
        }
    }

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

    /**
     * TNT auto-prime: when a player places TNT during PvP, the block is
     * replaced with a primed TNT entity instead of sitting inert.
     * Players don't need flint and steel.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        if (!plugin.getGameManager().isPvpAllowed()) return;
        if (event.getBlock().getType() != Material.TNT) return;

        Player placer = event.getPlayer();
        if (plugin.getGameManager().get(placer.getUniqueId()) == null) return;
        if (placer.getGameMode() == GameMode.SPECTATOR) return;

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
        tnt.setFuseTicks(80);

        block.getWorld().playSound(block.getLocation(),
                Sound.ENTITY_TNT_PRIMED, 1f, 1f);
    }
}
