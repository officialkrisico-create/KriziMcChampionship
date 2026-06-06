package nl.kmc.core.api;

/**
 * Root entry point for game plugins.
 * Each sub-interface has a narrow, focused contract.
 *
 * <pre>{@code
 * KMCApi api = KMCApiProvider.get();
 * api.teams().getTeamByPlayer(uuid).ifPresent(...);
 * api.points().givePoints(uuid, 50, PointAward.Reason.KILL, "skywars");
 * }</pre>
 */
public interface KMCApi {

    TeamApi        teams();
    PointsApi      points();
    GameApi        games();
    StatsApi       stats();
    AchievementApi achievements();

    /**
     * Translates {@code key} into the given player's chosen language, with
     * {@code {0}}, {@code {1}}, … placeholder substitution and {@code &} colour
     * codes. Falls back to the raw key when no i18n backend is available, so
     * games can always call this safely.
     */
    default String tr(java.util.UUID player, String key, Object... args) { return key; }
}
