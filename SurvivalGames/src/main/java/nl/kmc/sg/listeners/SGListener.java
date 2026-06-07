package nl.kmc.sg.listeners;

import nl.kmc.sg.SurvivalGamesPlugin;
import nl.kmc.sg.managers.SurvivalGamesManagerV2;
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

    private SurvivalGamesManagerV2 gm() { return plugin.getGameManagerV2(); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        SurvivalGamesManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerStats ps = gm.getStatsMap().get(p.getUniqueId());
        if (ps == null) return;

        if (p.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }

        // No damage during non-PvP phases
        if (!gm.isPvpAllowed()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        SurvivalGamesManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
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

        if (!gm.isPvpAllowed()) {
            event.setCancelled(true);
            return;
        }

        gm.recordAttack(victim.getUniqueId(), attacker.getUniqueId());

        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            gm.handleDeath(victim, attacker, "killed");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        SurvivalGamesManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
        Player p = event.getEntity();
        if (gm.getStatsMap().get(p.getUniqueId()) == null) return;

        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);

        Player killer = gm.getRecentAttacker(p.getUniqueId());
        gm.handleDeath(p, killer, "stierf");
    }

    /**
     * TNT auto-prime: when a player places TNT during PvP, the block is
     * replaced with a primed TNT entity instead of sitting inert.
     * Players don't need flint and steel.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        SurvivalGamesManagerV2 gm = gm();
        if (gm == null || !gm.isPvpAllowed()) return;
        if (event.getBlock().getType() != Material.TNT) return;

        Player placer = event.getPlayer();
        if (gm.getStatsMap().get(placer.getUniqueId()) == null) return;
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

    /** When a chest is looted empty during the match, break it so the map shows what's left. */
    @EventHandler
    public void onChestEmptied(org.bukkit.event.inventory.InventoryCloseEvent e) {
        SurvivalGamesManagerV2 m = gm();
        if (m == null || !m.getState().isRunning()) return;
        breakChestIfEmpty(e.getInventory());
    }

    static void breakChestIfEmpty(org.bukkit.inventory.Inventory inv) {
        var holder = inv.getHolder();
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        if (holder instanceof org.bukkit.block.Chest c) {
            blocks.add(c.getBlock());
        } else if (holder instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide()  instanceof org.bukkit.block.Chest lc) blocks.add(lc.getBlock());
            if (dc.getRightSide() instanceof org.bukkit.block.Chest rc) blocks.add(rc.getBlock());
        } else {
            return;
        }
        for (ItemStack it : inv.getContents())
            if (it != null && it.getType() != Material.AIR) return; // not empty yet

        for (Block b : blocks) {
            if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
            var data = b.getBlockData();
            b.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 20, data);
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOOD_BREAK, 1f, 1f);
            b.setType(Material.AIR);
        }
    }
}
