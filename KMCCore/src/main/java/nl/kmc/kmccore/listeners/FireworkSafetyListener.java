package nl.kmc.kmccore.listeners;

import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Ceremony fireworks are decoration: a spawned firework (no shooter) never hurts a player. Fireworks that
 * were fired by someone (crossbow, a game mechanic) are left alone.
 */
public final class FireworkSafetyListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getDamager() instanceof Firework fw && fw.getShooter() == null) {
            event.setCancelled(true);
        }
    }
}
