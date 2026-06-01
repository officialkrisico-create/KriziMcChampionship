package nl.kmc.game.api;

import java.util.*;
import java.util.logging.Logger;

/** Global registry of per-game intro cards. Game plugins register on startup. */
public final class GameIntroCardRegistry {

    private static final Logger LOG = Logger.getLogger(GameIntroCardRegistry.class.getName());
    private static final Map<String, GameIntroCard> cards = new LinkedHashMap<>();

    private GameIntroCardRegistry() {}

    public static void register(GameIntroCard card) {
        cards.put(card.getGameId(), card);
        LOG.info("[KMC/Intro] Registered intro card for: " + card.getGameId());
    }

    public static Optional<GameIntroCard> get(String gameId) {
        return Optional.ofNullable(cards.get(gameId));
    }

    public static Collection<GameIntroCard> getAll() {
        return Collections.unmodifiableCollection(cards.values());
    }
}
