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
}
