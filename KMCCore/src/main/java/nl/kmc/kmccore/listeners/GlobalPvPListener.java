package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Global PvP disabler for the KMC tournament.
 *
 * <p>Cancels ALL player-vs-player damage by default. Specific minigames
 * that want PvP (like Survival Games or The Bridge) can un-cancel the
 * event in their own listener at {@link EventPriority#HIGHEST}.
 *
 * <p>This runs at NORMAL priority so minigames can override at HIGHEST.
 */
public class GlobalPvPListener implements Listener {

    private final KMCCore plugin;

    public GlobalPvPListener(KMCCore plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity()  instanceof Player victim)) return;

        // A running game that handles PvP itself (SurvivalGames, SkyWars, TNT Tag, ...) has opted out of
        // this rule: its own listeners cancel/apply the damage. Cancelling here first would make them
        // never see the event (they listen with ignoreCancelled = true).
        if (nl.kmc.core.RunningGames.pvpHandledByGame(attacker.getUniqueId(), victim.getUniqueId())) return;

        // Everyone else: no PvP.
        event.setCancelled(true);
    }
}
