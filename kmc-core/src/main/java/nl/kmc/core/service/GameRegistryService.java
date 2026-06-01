package nl.kmc.core.service;

import nl.kmc.core.domain.GameRegistration;

import java.util.*;
import java.util.logging.Logger;

/**
 * Central registry where game plugins self-register on startup.
 * The tournament engine queries this to know which games are available.
 */
public final class GameRegistryService {

    private static final Logger LOG = Logger.getLogger(GameRegistryService.class.getName());

    private final Map<String, GameRegistration> registry   = new LinkedHashMap<>();
    private final List<String>                  playedIds  = new ArrayList<>();
    private String                              activeGameId;

    public void register(GameRegistration registration) {
        registry.put(registration.getId(), registration);
        LOG.info("[KMC/GameRegistry] Registered: " + registration.getId()
                 + " (" + registration.getDisplayName() + ")");
    }

    public Optional<GameRegistration> get(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public Collection<GameRegistration> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /** Returns games not yet played in this tournament rotation. */
    public List<GameRegistration> getUnplayed() {
        return registry.values().stream()
                .filter(g -> !playedIds.contains(g.getId()))
                .toList();
    }

    public Optional<GameRegistration> getActive() {
        if (activeGameId == null) return Optional.empty();
        return get(activeGameId);
    }

    public void setActive(String gameId) { this.activeGameId = gameId; }

    public void clearActive() { this.activeGameId = null; }

    public void markPlayed(String gameId) {
        if (!playedIds.contains(gameId)) playedIds.add(gameId);
    }

    public List<String> getPlayedIds() { return List.copyOf(playedIds); }

    public void resetPlayedList() { playedIds.clear(); }

    public boolean isRegistered(String gameId) { return registry.containsKey(gameId); }
}
