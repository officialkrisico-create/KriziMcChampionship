package nl.kmc.game.api;

/** Phases of a single game instance. */
public enum GameState {
    IDLE,
    PREPARING,
    COUNTDOWN,
    GRACE,      // grace period at start — PvP disabled
    ACTIVE,
    DEATHMATCH,
    ENDED;

    public boolean isRunning()   { return this == COUNTDOWN || this == GRACE || this == ACTIVE || this == DEATHMATCH; }
    public boolean isPvPActive() { return this == ACTIVE || this == DEATHMATCH; }
    public boolean isOver()      { return this == ENDED; }
    public boolean isIdle()      { return this == IDLE; }
}
