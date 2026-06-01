package nl.kmc.core.domain;

/**
 * Maps an achievement to the event(s) the AchievementService evaluates it against.
 * Each value corresponds to one or more Bukkit/KMC events.
 */
public enum AchievementTrigger {

    /** KMCKillEvent — lifetime kill counter. */
    KILL_COUNT,

    /** KMCKillEvent — kills within a single game session (resets on GameStartEvent). */
    KILL_COUNT_IN_GAME,

    /** KMCKillEvent — kill streak within a game session (no gap > 30 s). */
    KILL_STREAK_IN_GAME,

    /** GameEndEvent — player placed 1st OR is listed as the MVP. */
    GAME_WIN,

    /** GameEndEvent (game-specific) — placed 1st in a specific game (scopeGameId). */
    GAME_WIN_SPECIFIC,

    /** Derived from GAME_WIN + no deaths tracked during that game session. */
    GAME_WIN_NO_DEATH,

    /** Consecutive GAME_WIN events without a non-win game in between. */
    GAME_WIN_STREAK,

    /** GameEndEvent — any placement (lifetime games played counter). */
    GAMES_PLAYED,

    /** TournamentEndEvent — player is a member of the winning team. */
    TOURNAMENT_WIN,

    /** TournamentEndEvent — player ranked #1 in individual points. */
    TOURNAMENT_MVP,

    /** TournamentEndEvent — player participated (any finish). */
    TOURNAMENT_PLAYED,

    /** PointsAwardedEvent — total points accumulated in the current tournament. */
    POINTS_IN_TOURNAMENT,

    /** ClutchMomentEvent — specific ClutchType match. */
    CLUTCH_MOMENT,

    /** PlayerEliminatedEvent — first time the player is ever eliminated. */
    PLAYER_FIRST_ELIMINATION,

    /** Bukkit PlayerDeathEvent — cause matches (VOID, LAVA, ENTITY_ATTACK, etc.). */
    PLAYER_DEATH_CAUSE,

    /** PlayerEliminatedEvent — cumulative eliminations across one tournament. */
    ELIMINATIONS_IN_TOURNAMENT,

    /**
     * GameObjectiveEvent — game emits a typed objective completion.
     * Matched by scopeGameId and objectiveType on the definition.
     */
    GAME_OBJECTIVE,

    /** Admin-only. Never triggered automatically; only via /kmcachievements grant. */
    MANUAL
}
