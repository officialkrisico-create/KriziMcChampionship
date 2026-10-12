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

    /**
     * Reports a notable performance for the end-of-tournament awards (categories: {@code clutch},
     * {@code racer}, {@code parkour}, {@code teammate}; others are kept but not shown). {@code value} is
     * how much it counts for (e.g. 1 per clutch save, a placement score per race). No-op by default.
     */
    default void recordHighlight(java.util.UUID player, String category, int value, String gameId) {}

    /**
     * A player was knocked out of the game by {@code killer} (may be null). Lets the platform give the
     * victim a short kill-cam. No-op by default.
     */
    default void announceElimination(java.util.UUID victim, java.util.UUID killer, String gameId) {}

    /**
     * A racer finished with this placement. Lets the platform show a finish-cam for the winner.
     * No-op by default.
     */
    default void announceFinish(java.util.UUID finisher, int placement, int total, String gameId) {}

    /**
     * Reports a finished sector / lap / run on a race track for the track records.
     *
     * @param sector 0 = the whole run (lap or course), 1.. = sector number
     * @param millis time in milliseconds
     */
    default void recordSector(String gameId, String trackId, int sector, java.util.UUID player,
                              String playerName, long millis) {}

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
