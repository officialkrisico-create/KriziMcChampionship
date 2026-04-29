package nl.kmc.bridge.managers;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AssistManager implements Listener {
    /** victim → list of (attacker, timestamp) */
    private final Map<UUID, List<Hit>> hits = new ConcurrentHashMap<>();

    private static class Hit {
        UUID attacker; long whenMs;
        Hit(UUID a, long t) { attacker = a; whenMs = t; }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (victim.equals(attacker)) return;
        hits.computeIfAbsent(victim.getUniqueId(), k -> new ArrayList<>())
                .add(new Hit(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    /**
     * Returns the assist credit for a victim's death.
     * Most-recent attacker (other than killer) within window gets it.
     */
    public UUID consumeAssist(UUID victim, UUID killer, long windowMs) {
        List<Hit> list = hits.remove(victim);
        if (list == null) return null;
        long cutoff = System.currentTimeMillis() - windowMs;
        UUID assistUuid = null;
        long bestT = 0;
        for (Hit h : list) {
            if (h.whenMs < cutoff) continue;
            if (killer != null && h.attacker.equals(killer)) continue;
            if (h.whenMs > bestT) { bestT = h.whenMs; assistUuid = h.attacker; }
        }
        return assistUuid;
    }

    /** Clear when a player respawns (their hit history is irrelevant). */
    public void clearVictim(UUID uuid) { hits.remove(uuid); }
}
