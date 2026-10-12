package nl.kmc.tnttag.listeners;

import nl.kmc.tnttag.TNTTagPlugin;
import nl.kmc.tnttag.managers.TNTTagGameManagerV2;
import nl.kmc.tnttag.models.PlayerState;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.UUID;

/**
 * TNT Tag listeners — bomb transfer on hit, powerup pickup, and keeping the
 * arena/inventory locked down during a match.
 */
public class TagListener implements Listener {

    private final TNTTagPlugin plugin;

    public TagListener(TNTTagPlugin plugin) { this.plugin = plugin; }

    private TNTTagGameManagerV2 gm() { return plugin.getTntManagerV2(); }
    private boolean live() { TNTTagGameManagerV2 m = gm(); return m != null && m.isLive(); }
    private boolean running() { TNTTagGameManagerV2 m = gm(); return m != null && m.isRunning(); }
    private PlayerState state(UUID u) { TNTTagGameManagerV2 m = gm(); return m == null ? null : m.getPlayersMap().get(u); }

    /** Hitting a decoy NPC wastes the carrier's hit. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHitDecoy(EntityDamageByEntityEvent event) {
        if (!running()) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (plugin.getPowerupManager().popDecoy(event.getEntity())) event.setCancelled(true);
    }

    /** Right-click with a stored powerup uses it. */
    @EventHandler
    public void onUsePowerup(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!live()) return;
        var act = event.getAction();
        if (act != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && act != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.getPowerupManager().isPowerupItem(event.getItem())) return;
        event.setCancelled(true);
        PlayerState ps = state(event.getPlayer().getUniqueId());
        if (ps == null || !ps.isAlive()) return;
        plugin.getPowerupManager().use(event.getPlayer());
    }

    /** Hitting another player passes the bomb (if you hold it). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!live()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity()  instanceof Player victim)) return;
        event.setCancelled(true); // no real damage — only bomb transfer
        PlayerState aPs = state(attacker.getUniqueId());
        if (aPs == null || !aPs.isIt() || !aPs.isAlive()) return;
        gm().attemptTransfer(attacker.getUniqueId(), victim.getUniqueId());
    }

    /** Pick up a powerup. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!live()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        PlayerState ps = state(p.getUniqueId());
        Item item = event.getItem();
        if (ps == null || !ps.isAlive()) { event.setCancelled(true); return; }
        if (plugin.getPowerupManager().isPowerupItem(item.getItemStack()) && !plugin.getPowerupManager().canHold(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendActionBar(net.kyori.adventure.text.Component.text("§cGebruik eerst je huidige powerup (rechtsklik)!"));
            return;
        }
        if (plugin.getPowerupManager().handlePickup(p, item)) {
            event.setCancelled(true); // we consume it ourselves
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!running()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (state(p.getUniqueId()) == null) return;
        // The closing border damages with CUSTOM damage; a lethal tick eliminates instead of killing.
        if (event.getCause() == EntityDamageEvent.DamageCause.CUSTOM) {
            if (event.getFinalDamage() >= p.getHealth()) {
                event.setCancelled(true);
                gm().eliminateEnvironmental(p);
            }
            return;
        }
        // No other real damage — eliminations come only from the round explosion / border.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!running()) return;
        if (state(event.getPlayer().getUniqueId()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!running()) return;
        if (state(event.getPlayer().getUniqueId()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!running()) return;
        if (state(event.getPlayer().getUniqueId()) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!running()) return;
        if (state(event.getPlayer().getUniqueId()) != null) event.setCancelled(true);
    }

    /** Don't let players take their TNT helmet off. */
    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!running()) return;
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (state(p.getUniqueId()) == null) return;
        if (event.getView().getType() == InventoryType.CRAFTING) {
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.TNT) {
                event.setCancelled(true); return;
            }
            if (event.getSlot() == 39) event.setCancelled(true); // helmet slot
        }
    }
}
