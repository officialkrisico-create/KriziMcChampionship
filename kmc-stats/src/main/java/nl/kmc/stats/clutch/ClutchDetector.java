package nl.kmc.stats.clutch;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.stats.model.GameStats;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Interface for a single clutch detection algorithm. */
public interface ClutchDetector {

    /** Return a clutch event if detected, empty if not. */
    Optional<ClutchMomentEvent> evaluate(
            UUID playerUuid,
            String playerName,
            String gameId,
            GameStats currentStats,
            Map<UUID, GameStats> allActiveStats,
            Context ctx
    );

    /** Context carries game-state metadata that detectors need without tight coupling. */
    record Context(
            int totalPlayers,
            int remainingPlayers,
            long gameElapsedSeconds,
            long gameTotalSeconds,
            boolean gameNearEnd,
            int playerTeamSize,
            int playerTeamAliveCount
    ) {}
}
