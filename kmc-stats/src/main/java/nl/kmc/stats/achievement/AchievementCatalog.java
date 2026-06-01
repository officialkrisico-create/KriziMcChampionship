package nl.kmc.stats.achievement;

import nl.kmc.core.domain.AchievementDefinition;
import nl.kmc.core.domain.AchievementTrigger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Runtime registry of all loaded {@link AchievementDefinition}s.
 * Thread-safe reads; reload is only triggered from the main thread.
 *
 * <p>Two volatile maps are swapped atomically on reload:
 * <ul>
 *   <li>{@code byId} — O(1) lookup by achievement id</li>
 *   <li>{@code byTrigger} — O(1) lookup by trigger type, pre-built at load time</li>
 * </ul>
 */
public final class AchievementCatalog {

    private static final Logger LOG = Logger.getLogger(AchievementCatalog.class.getName());

    private volatile Map<String, AchievementDefinition>              byId      = new ConcurrentHashMap<>();
    private volatile Map<AchievementTrigger, List<AchievementDefinition>> byTrigger = new ConcurrentHashMap<>();

    /** Replaces the entire catalog atomically. */
    public void load(Collection<AchievementDefinition> defs) {
        Map<String, AchievementDefinition> idMap = new ConcurrentHashMap<>();
        Map<AchievementTrigger, List<AchievementDefinition>> trigMap = new ConcurrentHashMap<>();

        for (AchievementDefinition d : defs) {
            idMap.put(d.getId(), d);
            trigMap.computeIfAbsent(d.getTrigger(), k -> new ArrayList<>()).add(d);
        }

        // Make lists unmodifiable before publishing
        trigMap.replaceAll((k, v) -> List.copyOf(v));

        byId      = idMap;
        byTrigger = trigMap;
        LOG.info("[KMC/Achievements] Catalog loaded: " + idMap.size() + " definitions.");
    }

    public AchievementDefinition get(String id) { return byId.get(id); }

    public Collection<AchievementDefinition> getAll() { return Collections.unmodifiableCollection(byId.values()); }

    /** O(1) lookup — returns an immutable list (empty if none registered for this trigger). */
    public List<AchievementDefinition> getByTrigger(AchievementTrigger trigger) {
        return byTrigger.getOrDefault(trigger, List.of());
    }

    public int size() { return byId.size(); }
}
