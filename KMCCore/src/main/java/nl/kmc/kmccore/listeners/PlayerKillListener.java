package nl.kmc.kmccore.listeners;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Listens for player deaths to award kill points to the killer.
 */
public class PlayerKillListener implements Listener {

    private final KMCCore plugin;

    public PlayerKillListener(KMCCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Only count kills during an active tournament game
        if (!plugin.getTournamentManager().isActive()) return;
        if (!plugin.getGameManager().isGameActive())   return;

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (killer.equals(event.getEntity())) return; // self-kill

        // Per-game handlers (e.g. SkyWars, SurvivalGames, LuckyBlock) credit
        // kills with their own per-game point values via givePoints().
        // The set below lists games that take responsibility — the global
        // award is suppressed for those so points don't double-count.
        var active = plugin.getGameManager().getActiveGame();
        if (active != null) {
            switch (active.getId()) {
                case "skywars":
                case "survival_games":
                case "spleef_teams":
                case "spleef":
                case "quake_craft":
                case "quakecraft":
                case "lucky_block":
                case "luckyblock":
                case "the_bridge":
                case "bridge":
                case "tnt_tag":
                case "tnttag":
                case "mob_mayhem":
                case "mobmayhem":
                    return;  // per-game listener handles this
                default:
                    // Fall through to global award
            }
        }

        plugin.getPointsManager().awardKill(killer.getUniqueId());
    }
}
