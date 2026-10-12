package nl.kmc.formula.listeners;

import nl.kmc.formula.FormulaMCPlugin;
import nl.kmc.formula.managers.FormulaGameManagerV2;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/**
 * Keeps the race fair: nobody can leave their boat or break it, and a race boat never collides with another
 * entity (belt and braces next to the team collision rules the game switches on).
 */
public final class RaceListener implements Listener {

    private final FormulaMCPlugin plugin;

    public RaceListener(FormulaMCPlugin plugin) { this.plugin = plugin; }

    private FormulaGameManagerV2 gm() { return plugin.getGameManagerV2(); }

    /** Boats cannot be left during the race (shift does nothing). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExit(VehicleExitEvent e) {
        FormulaGameManagerV2 gm = gm();
        if (gm == null || !gm.isRaceBoat(e.getVehicle())) return;
        if (e.getExited() instanceof Player p && gm.isRacing(p.getUniqueId())) e.setCancelled(true);
        else if (e.getExited() instanceof Player p && gm.getState().isRunning() && gm.hasRacer(p.getUniqueId())) {
            e.setCancelled(true);   // still on the grid
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent e) {
        FormulaGameManagerV2 gm = gm();
        if (gm != null && gm.isRaceBoat(e.getVehicle())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(VehicleDamageEvent e) {
        FormulaGameManagerV2 gm = gm();
        if (gm != null && gm.isRaceBoat(e.getVehicle())) e.setCancelled(true);
    }

    /** No collisions at all — not with other boats, not with players, not with mobs. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCollide(VehicleEntityCollisionEvent e) {
        FormulaGameManagerV2 gm = gm();
        if (gm == null) return;
        if (gm.isRaceBoat(e.getVehicle()) || gm.isRaceBoat(e.getEntity())) {
            e.setCancelled(true);   // cancels the collision and the pickup in one go
        }
    }

    /** Right-click with a power-up item uses it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUsePowerup(org.bukkit.event.player.PlayerInteractEvent e) {
        FormulaGameManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!gm.hasRacer(e.getPlayer().getUniqueId())) return;
        if (!gm.isPowerupItem(e.getItem())) return;
        e.setCancelled(true);
        gm.usePowerup(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent e) {
        FormulaGameManagerV2 gm = gm();
        if (gm == null || !gm.getState().isRunning()) return;
        if (e.getEntity() instanceof Player p && gm.hasRacer(p.getUniqueId())) e.setCancelled(true);
    }
}
