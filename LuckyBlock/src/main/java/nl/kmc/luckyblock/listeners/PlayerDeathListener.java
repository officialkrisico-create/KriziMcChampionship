package nl.kmc.luckyblock.listeners;

import nl.kmc.game.api.AssistTracker;
import nl.kmc.luckyblock.LuckyBlockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import java.util.UUID;

/**
 * Listens for player deaths during Lucky Block and triggers elimination.
 * Also awards coins to the killer via KMCCore.
 */
public class PlayerDeathListener implements Listener {

    private final LuckyBlockPlugin plugin;
    private final AssistTracker assistTracker = new AssistTracker();

    public PlayerDeathListener(LuckyBlockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getGameState().isActive()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null) return;

        assistTracker.recordHit(victim.getUniqueId(), attacker.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getGameState().isActive()) return;

        Player dead   = event.getEntity();
        Player killer = dead.getKiller();

        // Suppress default death message — GameStateManager broadcasts its own
        event.setDeathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(false);

        // Award kill points using Lucky Block's per-game value (20 by default).
        // Goes through KMCApi.givePoints so the round multiplier still applies.
        if (killer != null && !killer.equals(dead)) {
            int killPts = plugin.getConfig().getInt("points.per-kill", 20);
            if (killPts > 0) {
                UUID assistId = assistTracker.getAssist(dead.getUniqueId());
                if (killer.getUniqueId().equals(assistId)) assistId = null;
                var split = AssistTracker.split(killPts, assistId,
                        plugin.getConfig().getDouble("points.assist-fraction", 0.2));

                plugin.getKmcCore().getApi().givePoints(killer.getUniqueId(), split.killerAmount());
                killer.sendMessage("§6+" + split.killerAmount() + " punten voor de kill!");

                if (assistId != null && split.assistAmount() > 0) {
                    plugin.getKmcCore().getApi().givePoints(assistId, split.assistAmount());
                    Player assistPlayer = Bukkit.getPlayer(assistId);
                    if (assistPlayer != null)
                        assistPlayer.sendMessage("§e+" + split.assistAmount() + " §7punten voor de assist op §f" + dead.getName());
                }
            }
            // Track the kill in HoF + per-player stats (not the points)
            plugin.getKmcCore().getHallOfFameManager().recordKill(killer);
        }
        assistTracker.clear(dead.getUniqueId());

        // Eliminate from game (teleport to waiting room etc.)
        plugin.getGameState().eliminatePlayer(dead.getUniqueId());
    }
}
