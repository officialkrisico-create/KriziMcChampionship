package nl.kmc.parkour.listeners;

import nl.kmc.parkour.ParkourWarriorPlugin;
import nl.kmc.parkour.models.Checkpoint;
import nl.kmc.parkour.models.Powerup;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all in-game movement-driven events:
 *
 * <ul>
 *   <li>Walking into a checkpoint trigger box → score</li>
 *   <li>Walking into a powerup zone → apply effect (with cooldown)</li>
 *   <li>Fall / lava / drowning damage → respawn at last checkpoint</li>
 * </ul>
 */
public class MovementListener implements Listener {

    private final ParkourWarriorPlugin plugin;

    /** Per-player checkpoint cooldown — 1.5s — prevents re-trigger on standing still. */
    private final Map<UUID, Long> checkpointCooldown = new HashMap<>();

    /** Per-player powerup cooldown — 5s — prevents constant re-application. */
    private final Map<UUID, Map<String, Long>> powerupCooldown = new HashMap<>();

    public MovementListener(ParkourWarriorPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (event.getTo() == null) return;

        // Only on block-position changes — skip same-block moves
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player p = event.getPlayer();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;
        long now = System.currentTimeMillis();

        // ---- Checkpoint detection ----
        Checkpoint cp = plugin.getCourseManager().findCheckpointAt(event.getTo());
        if (cp != null) {
            Long expire = checkpointCooldown.get(p.getUniqueId());
            if (expire == null || now > expire) {
                checkpointCooldown.put(p.getUniqueId(), now + 1500);
                plugin.getGameManager().handleCheckpointEntry(p, cp);
            }
            return;
        }

        // ---- Powerup detection ----
        Powerup pu = plugin.getCourseManager().findPowerupAt(event.getTo());
        if (pu != null) {
            var perPlayer = powerupCooldown.computeIfAbsent(
                    p.getUniqueId(), k -> new HashMap<>());
            Long expire = perPlayer.get(pu.getId());
            if (expire == null || now > expire) {
                // Cooldown = duration + 1s grace
                perPlayer.put(pu.getId(), now + (pu.getDurationSeconds() + 1) * 1000L);
                plugin.getGameManager().applyPowerup(p, pu);
            }
        }
    }

    /**
     * Treat all damage as a death — parkour shouldn't have HP loss; players
     * should respawn instantly on any damage source (fall, lava, etc.).
     *
     * <p>Cancel the damage event itself, then respawn via GameManager.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;

        // Cancel the damage and respawn
        event.setCancelled(true);
        plugin.getGameManager().handleDeath(p);
    }

    /**
     * Defensive net — if the player somehow dies anyway, respawn them.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getGameManager().isActive()) return;
        Player p = event.getEntity();
        if (plugin.getGameManager().get(p.getUniqueId()) == null) return;

        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);
        // The respawn will be handled by handleDeath via void check or
        // PlayerRespawnEvent — for now just record it
        plugin.getGameManager().handleDeath(p);
    }
}
