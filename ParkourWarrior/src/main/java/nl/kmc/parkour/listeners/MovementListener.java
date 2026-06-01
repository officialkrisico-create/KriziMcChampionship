package nl.kmc.parkour.listeners;

import nl.kmc.parkour.ParkourWarriorPlugin;
import nl.kmc.parkour.managers.ParkourGameManagerV2;
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

    private ParkourGameManagerV2 v2() { return plugin.getParkourManagerV2(); }

    private boolean isGameActive() {
        ParkourGameManagerV2 mgr = v2();
        return mgr != null && mgr.isActive();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isGameActive()) return;
        if (event.getTo() == null) return;

        // Only on block-position changes — skip same-block moves
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player p = event.getPlayer();
        if (v2().getRunner(p.getUniqueId()) == null) return;
        long now = System.currentTimeMillis();

        // ---- Checkpoint detection ----
        Checkpoint cp = plugin.getCourseManager().findCheckpointAt(event.getTo());
        if (cp != null) {
            Long expire = checkpointCooldown.get(p.getUniqueId());
            if (expire == null || now > expire) {
                checkpointCooldown.put(p.getUniqueId(), now + 1500);
                v2().handleCheckpointEntry(p, cp);
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
                v2().applyPowerup(p, pu);
            }
        }
    }

    /**
     * Treat all damage as a death — parkour shouldn't have HP loss; players
     * should respawn instantly on any damage source (fall, lava, etc.).
     *
     * <p>Cancel the damage event itself, then respawn via V2 GameManager.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!isGameActive()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (v2().getRunner(p.getUniqueId()) == null) return;

        // Cancel the damage and respawn
        event.setCancelled(true);
        v2().handleDeath(p);
    }

    /**
     * Defensive net — if the player somehow dies anyway, respawn them.
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!isGameActive()) return;
        Player p = event.getEntity();
        if (v2().getRunner(p.getUniqueId()) == null) return;

        event.deathMessage(null);
        event.getDrops().clear();
        event.setKeepInventory(true);
        v2().handleDeath(p);
    }
}
