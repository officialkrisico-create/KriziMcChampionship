package nl.kmc.core.setup;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry of {@link GameSetup} descriptors. Game plugins register here
 * on enable; KMCCore's Setup Dashboard reads it to build a single, unified
 * setup UI for every game.
 *
 * <p>Lives in {@code kmc-core} so both KMCCore (reader) and the game plugins
 * (writers) can reach it without a circular dependency.
 */
public final class SetupService {

    private final Map<String, GameSetup> registry = new LinkedHashMap<>();

    public void register(GameSetup setup) {
        if (setup != null) registry.put(setup.gameId(), setup);
    }

    public Optional<GameSetup> get(String gameId) {
        return Optional.ofNullable(registry.get(gameId));
    }

    public Collection<GameSetup> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public boolean isRegistered(String gameId) { return registry.containsKey(gameId); }
}
