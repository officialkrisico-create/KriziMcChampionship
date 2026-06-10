package nl.kmc.core.api;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.TournamentPhase;

import java.util.Optional;

/** Game lifecycle surface — used by game plugins to signal state to the tournament engine. */
public interface GameApi {

    /**
     * Acquires the global scoreboard lock so this game can own the sidebar.
     *
     * @param gameId the requesting game's id
     * @return true if acquired, false if another game already holds it
     */
    boolean acquireScoreboard(String gameId);

    void releaseScoreboard(String gameId);

    /**
     * Registers the sidebar content this game shows while it owns the
     * scoreboard. Pass {@code null} (or call {@link #clearScoreboard}) to fall
     * back to the frozen lobby sidebar. No-op by default so non-tournament
     * API impls don't have to implement it.
     */
    default void setScoreboard(String gameId, GameScoreboard board) {}

    /** Clears any per-game sidebar previously set by {@code gameId}. */
    default void clearScoreboard(String gameId) {}

    /**
     * Records the MVP of a finished mini-game (the game's own top performer).
     * Drives the "GAME MVP" reveal and tournament/lifetime MVP tracking.
     * No-op by default. {@code mvp} may be null (no MVP this game).
     */
    default void recordGameMvp(java.util.UUID mvp, String name, String gameId) {}

    boolean isScoreboardOwnedBy(String gameId);

    Optional<String> getScoreboardOwner();

    /** Signals that the game has finished — hands control back to the tournament engine. */
    void signalGameEnd(String gameId, String winnerDescription);

    /** Records a participation entry for tournament statistics. */
    void recordGameParticipation(java.util.UUID playerUuid, String playerName,
                                 String gameId, boolean won);

    Optional<GameRegistration> getActiveGame();

    int getCurrentRound();

    boolean isTournamentActive();

    TournamentPhase getCurrentPhase();
}
