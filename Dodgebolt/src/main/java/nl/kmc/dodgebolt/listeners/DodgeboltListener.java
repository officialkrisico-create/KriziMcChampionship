package nl.kmc.dodgebolt.listeners;

import nl.kmc.dodgebolt.DodgeboltPlugin;
import nl.kmc.dodgebolt.managers.DodgeboltGameManagerV2;
import nl.kmc.dodgebolt.models.DbPlayer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Dodgebolt rules: arrows eliminate, nothing else hurts, the bow stays in the hand. */
public final class DodgeboltListener implements Listener {

    private final DodgeboltPlugin plugin;

    public DodgeboltListener(DodgeboltPlugin plugin) { this.plugin = plugin; }

    private DodgeboltGameManagerV2 running() {
        DodgeboltGameManagerV2 gm = plugin.getGameManagerV2();
        return gm != null && gm.getState().isRunning() ? gm : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null || !(e.getEntity() instanceof Player victim) || !gm.isParticipant(victim.getUniqueId())) return;
        e.setCancelled(true);                                   // nothing deals damage — arrows eliminate
        if (!(e.getDamager() instanceof Projectile proj) || !(proj.getShooter() instanceof Player shooter)) return;
        if (!gm.isRoundLive()) return;
        DbPlayer s = gm.get(shooter.getUniqueId());
        DbPlayer v = gm.get(victim.getUniqueId());
        if (s == null || v == null || !s.isAlive() || !v.isAlive()) return;
        if (s.getSide() == v.getSide()) return;                // no friendly fire
        proj.remove();
        gm.eliminate(victim.getUniqueId(), shooter.getUniqueId(), "arrow");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnyDamage(EntityDamageEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null || !(e.getEntity() instanceof Player p) || !gm.isParticipant(p.getUniqueId())) return;
        e.setCancelled(true);                                   // the void is handled by position
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null) return;
        if (e.getEntity() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player p
                && gm.isParticipant(p.getUniqueId())) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);   // arrows that land can be picked up again
            if (!gm.isRoundLive()) e.setCancelled(true);
        }
    }

    /**
     * Drawing and releasing the bow before the round starts must not cost the arrow: cancelling the shot
     * itself (not just the launched arrow) keeps the arrow in the quiver.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onShoot(org.bukkit.event.entity.EntityShootBowEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null || !(e.getEntity() instanceof Player p) || !gm.isParticipant(p.getUniqueId())) return;
        if (gm.isRoundLive()) return;
        e.setCancelled(true);
        e.setConsumeItem(false);
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> p.updateInventory());
    }

    @EventHandler
    public void onArrowPickup(PlayerPickupArrowEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null) return;
        DbPlayer d = gm.get(e.getPlayer().getUniqueId());
        if (d == null || !d.isAlive() || !gm.isRoundLive()) e.setCancelled(true);
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null || !(e.getEntity() instanceof Player p)) return;
        DbPlayer d = gm.get(p.getUniqueId());
        if (d == null || !d.isAlive() || !gm.isRoundLive()) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm != null && gm.isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInventory(InventoryClickEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm != null && e.getWhoClicked() instanceof Player p && gm.isParticipant(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm != null && gm.isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm != null && gm.isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm != null && e.getEntity() instanceof Player p && gm.isParticipant(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null) return;
        DbPlayer d = gm.get(e.getPlayer().getUniqueId());
        if (d != null && d.isAlive() && gm.isRoundLive()) gm.eliminate(e.getPlayer().getUniqueId(), null, "quit");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        DodgeboltGameManagerV2 gm = running();
        if (gm == null || gm.isParticipant(e.getPlayer().getUniqueId())) return;
        Player p = e.getPlayer();
        p.setGameMode(GameMode.SPECTATOR);
        Location spot = plugin.getArena().spectator();
        if (spot == null) spot = plugin.getArena().center();
        if (spot != null) p.teleport(spot);
    }
}
