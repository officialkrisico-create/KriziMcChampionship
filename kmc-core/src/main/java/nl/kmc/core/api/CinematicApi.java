package nl.kmc.core.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Arena flyover playback surface — used by game plugins to show a short
 * cinematic camera orbit of their arena right before a match starts.
 *
 * <p>Routes are keyed {@code arena-{gameId}} by convention. If an admin
 * hasn't recorded one yet, implementations may auto-generate a simple orbit
 * around a supplied centre point instead of doing nothing.
 */
public interface CinematicApi {

    /**
     * Plays the {@code arena-{gameId}} flyover for {@code players}, calling
     * {@code onComplete} once it finishes (or immediately if there's nothing
     * to play). Default no-op so API implementations without a cinematic
     * backend don't have to implement this.
     *
     * @param gameId     the game whose arena flyover to play
     * @param players    who should see it
     * @param center     fallback orbit centre if no route is recorded yet (may be null)
     * @param radius     fallback orbit radius in blocks, used only when auto-generating
     * @param height     fallback orbit height above {@code center}, in blocks; 0 or less
     *                   auto-scales the height from {@code radius} instead
     * @param onComplete called on the main thread when playback ends
     * @return true if a flyover actually played
     */
    default boolean playArenaFlyover(String gameId, Collection<Player> players,
                                     Location center, double radius, double height, Runnable onComplete) {
        if (onComplete != null) onComplete.run();
        return false;
    }
}
