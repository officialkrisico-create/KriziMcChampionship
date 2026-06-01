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
