package nl.kmc.quake.managers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who killed each player most recently, so we can detect
 * "revenge kills" — when you kill the same player who killed you
 * within a configurable time window.
 *
 * <p>Usage in {@link GameManager#handleHit(org.bukkit.entity.Player, org.bukkit.entity.Player, String)}:
 * <pre>
 *   // Before recording the kill:
 *   boolean isRevenge = revengeTracker.checkRevenge(
 *       shooter.getUniqueId(), target.getUniqueId(), windowMs);
 *
 *   // After recording the kill:
 *   revengeTracker.recordKill(target.getUniqueId(), shooter.getUniqueId());
 *
 *   if (isRevenge) {
 *       int bonus = config.getInt("points.revenge-kill-bonus", 25);
 *       api.givePoints(shooter.getUniqueId(), bonus);
 *       broadcast("&dREVENGE! &7" + shooter.getName() + " &epakte wraak!");
 *   }
 * </pre>
 *
 * <p>Lightweight — just two map entries per kill, no listener required.
 * The cleanup happens automatically (entries are overwritten on each
 * new kill, and we check timestamps so stale entries are ignored).
 */
public class RevengeKillTracker {

    private static class KillRecord {
        final UUID killer;
        final long whenMs;
        KillRecord(UUID killer, long whenMs) {
            this.killer = killer;
            this.whenMs = whenMs;
        }
    }

    /** victim → record of who killed them and when */
    private final Map<UUID, KillRecord> lastKilledBy = new ConcurrentHashMap<>();

    /**
     * Record that {@code killer} just killed {@code victim}. Replaces
     * any prior record for that victim.
     */
    public void recordKill(UUID victim, UUID killer) {
        if (victim == null || killer == null) return;
        lastKilledBy.put(victim, new KillRecord(killer, System.currentTimeMillis()));
    }

    /**
     * Returns true if {@code shooter} killing {@code target} qualifies
     * as a revenge kill — i.e. {@code target} previously killed
     * {@code shooter} within the last {@code windowMs} milliseconds.
     *
     * <p>Consumes the record (so killing the same player twice doesn't
     * award the revenge bonus twice without the target killing you back
     * again first).
     */
    public boolean checkRevenge(UUID shooter, UUID target, long windowMs) {
        if (shooter == null || target == null) return false;
        KillRecord rec = lastKilledBy.get(shooter);
        if (rec == null) return false;
        if (!rec.killer.equals(target)) return false;
        if (System.currentTimeMillis() - rec.whenMs > windowMs) return false;
        // Consume — clear so we don't double-award
        lastKilledBy.remove(shooter);
        return true;
    }

    /** Clear all records — call at game start/end. */
    public void clear() {
        lastKilledBy.clear();
    }
}
