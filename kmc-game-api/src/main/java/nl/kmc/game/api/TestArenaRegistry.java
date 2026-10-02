package nl.kmc.game.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Games that can generate a small throw-away arena for themselves register here, so
 * {@code /kmctest} can set a game up for testing without anyone having to build a map.
 */
public final class TestArenaRegistry {

    /** Builds the game's test arena around {@code origin} and configures the game to use it. */
    @FunctionalInterface
    public interface Builder {
        /**
         * @param admin  the admin running the command (for chat feedback)
         * @param origin where to build — X/Z are the arena's anchor, Y is the (high) build level
         * @return where the admin should be teleported to look at it, or {@code null} for no teleport
         */
        Location build(Player admin, Location origin) throws Exception;
    }

    public record Entry(String gameId, String displayName, Builder builder) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private TestArenaRegistry() {}

    public static void register(String gameId, String displayName, Builder builder) {
        ENTRIES.put(gameId, new Entry(gameId, displayName, builder));
    }

    public static Optional<Entry> get(String gameId) { return Optional.ofNullable(ENTRIES.get(gameId)); }

    public static Collection<Entry> all() { return Collections.unmodifiableCollection(ENTRIES.values()); }
}
