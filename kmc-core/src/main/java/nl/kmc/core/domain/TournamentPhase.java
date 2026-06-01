package nl.kmc.core.domain;

/** All phases a tournament can be in. Drives the presentation engine and automation engine. */
public enum TournamentPhase {

    WAITING,
    READY_CHECK,
    OPENING_CEREMONY,
    TEAM_SHOWCASE,
    TOURNAMENT_OVERVIEW,
    GAME_LINEUP_SHOWCASE,
    VOTING_PHASE,
    GAME_INTRO,
    GAME_ACTIVE,
    GAME_END_CEREMONY,
    ROUND_END_CEREMONY,
    CLOSING_CEREMONY,
    ENDED;

    public boolean isPreGame() {
        return this == WAITING || this == READY_CHECK || this == OPENING_CEREMONY
                || this == TEAM_SHOWCASE || this == TOURNAMENT_OVERVIEW
                || this == GAME_LINEUP_SHOWCASE;
    }

    public boolean isCeremony() {
        return this == OPENING_CEREMONY || this == TEAM_SHOWCASE
                || this == TOURNAMENT_OVERVIEW || this == GAME_LINEUP_SHOWCASE
                || this == GAME_INTRO || this == GAME_END_CEREMONY
                || this == ROUND_END_CEREMONY || this == CLOSING_CEREMONY;
    }

    public boolean isGameRunning() { return this == GAME_ACTIVE; }
    public boolean isVoting()      { return this == VOTING_PHASE; }
    public boolean isOver()        { return this == ENDED; }
}
