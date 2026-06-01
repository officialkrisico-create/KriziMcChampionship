package nl.kmc.core.api;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.domain.PointAward;
import nl.kmc.core.domain.TournamentPhase;
import nl.kmc.core.service.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Wires the four sub-APIs together. Not exported to game plugins directly — use KMCApiProvider. */
public final class KMCApiImpl implements KMCApi {

    private final TeamApiImpl        teamApi;
    private final PointsApiImpl      pointsApi;
    private final GameApiImpl        gameApi;
    private final StatsApiImpl       statsApi;
    private       AchievementApi     achievementApi; // set after AchievementService wires up

    public KMCApiImpl(TeamService teams, PointsService points,
                      TournamentService tournament, GameRegistryService registry) {
        this.teamApi   = new TeamApiImpl(teams);
        this.pointsApi = new PointsApiImpl(points);
        this.gameApi   = new GameApiImpl(tournament, registry, points);
        this.statsApi  = new StatsApiImpl(teams);
    }

    /** Called by KMCCorePlugin after AchievementService is constructed. */
    public void setAchievementApi(AchievementApi api) { this.achievementApi = api; }

    @Override public TeamApi        teams()        { return teamApi; }
    @Override public PointsApi      points()       { return pointsApi; }
    @Override public GameApi        games()        { return gameApi; }
    @Override public StatsApi       stats()        { return statsApi; }
    @Override public AchievementApi achievements() { return achievementApi; }

    // ── Inner implementations (package-private adapters) ─────────────────────

    private record TeamApiImpl(TeamService svc) implements TeamApi {
        public Optional<KMCTeam>   getTeam(String id)           { return svc.getTeam(id); }
        public Optional<KMCTeam>   getTeamByPlayer(UUID uuid)   { return svc.getTeamByPlayer(uuid); }
        public List<KMCTeam>       getAllTeams()                 { return svc.getAllTeams(); }
        public List<KMCTeam>       getStandings()               { return svc.getStandings(); }
        public Optional<KMCPlayer> getPlayer(UUID uuid)         { return svc.getPlayer(uuid); }
        public KMCPlayer           getOrCreatePlayer(UUID u, String n) { return svc.getOrCreatePlayer(u, n); }
        public List<KMCPlayer>     getAllPlayers()               { return svc.getAllPlayers(); }
    }

    private record PointsApiImpl(PointsService svc) implements PointsApi {
        public void givePoints(UUID u, int a, PointAward.Reason r, String g) { svc.givePoints(u, a, r, g); }
        public void giveTeamPoints(String t, int a, PointAward.Reason r, String g) { svc.giveTeamPoints(t, a, r, g); }
        public void awardPlayerPlacement(UUID u, int p, int tot, String g) { svc.awardPlayerPlacement(u, p, tot, g); }
        public void awardTeamPlacement(String t, int p, String g)          { svc.awardTeamPlacement(t, p, g); }
        public double getCurrentMultiplier()                                { return svc.getCurrentMultiplier(); }
        public void setPoints(UUID u, int pts)                              { svc.setPoints(u, pts); }
        public void setTeamPoints(String t, int pts)                        { svc.setTeamPoints(t, pts); }
        public void adjustPoints(UUID u, int d, String r)                   { svc.adjustPoints(u, d, r); }
        public void adjustTeamPoints(String t, int d, String r)             { svc.adjustTeamPoints(t, d, r); }
    }

    private static final class GameApiImpl implements GameApi {
        private final TournamentService  tournament;
        private final GameRegistryService registry;
        private final PointsService      points;
        private String scoreboardOwner;

        GameApiImpl(TournamentService t, GameRegistryService r, PointsService p) {
            this.tournament = t; this.registry = r; this.points = p;
        }

        public boolean acquireScoreboard(String gameId) {
            if (scoreboardOwner != null && !scoreboardOwner.equals(gameId)) return false;
            scoreboardOwner = gameId; return true;
        }
        public void releaseScoreboard(String gameId) {
            if (gameId.equals(scoreboardOwner)) scoreboardOwner = null;
        }
        public boolean isScoreboardOwnedBy(String gameId) { return gameId.equals(scoreboardOwner); }
        public Optional<String> getScoreboardOwner()      { return Optional.ofNullable(scoreboardOwner); }

        public void signalGameEnd(String gameId, String winnerDesc) {
            releaseScoreboard(gameId);
            registry.markPlayed(gameId);
            registry.clearActive();
            points.flushAudits();
        }
        public void recordGameParticipation(UUID uuid, String name, String gameId, boolean won) {
            // Delegated to StatsService in kmc-stats — hook via event bus
        }
        public Optional<GameRegistration> getActiveGame()    { return registry.getActive(); }
        public int           getCurrentRound()               { return tournament.getCurrentRound(); }
        public boolean       isTournamentActive()            { return tournament.isActive(); }
        public TournamentPhase getCurrentPhase()             { return tournament.getPhase(); }
    }

    private record StatsApiImpl(TeamService teams) implements StatsApi {
        public List<KMCPlayer> getPlayerLeaderboard(int limit) { return teams.getAllPlayers().stream()
                .sorted(java.util.Comparator.comparingInt(KMCPlayer::getPoints).reversed())
                .limit(limit).toList(); }
        public List<KMCTeam> getTeamLeaderboard(int limit) { return teams.getStandings().stream()
                .limit(limit).toList(); }
        public int getKills(UUID u)       { return teams.getPlayer(u).map(KMCPlayer::getKills).orElse(0); }
        public int getDeaths(UUID u)      { return teams.getPlayer(u).map(KMCPlayer::getDeaths).orElse(0); }
        public int getGamesPlayed(UUID u) { return teams.getPlayer(u).map(KMCPlayer::getGamesPlayed).orElse(0); }
        public int getPoints(UUID u)      { return teams.getPlayer(u).map(KMCPlayer::getPoints).orElse(0); }
        public int getTeamPoints(String id) { return teams.getTeam(id).map(KMCTeam::getPoints).orElse(0); }
        public int getPlayerRank(UUID u) {
            List<KMCPlayer> lb = getPlayerLeaderboard(Integer.MAX_VALUE);
            for (int i = 0; i < lb.size(); i++) if (lb.get(i).getUuid().equals(u)) return i + 1;
            return -1;
        }
        public int getTeamRank(String id) {
            List<KMCTeam> standings = teams.getStandings();
            for (int i = 0; i < standings.size(); i++) if (standings.get(i).getId().equals(id)) return i + 1;
            return -1;
        }
    }
}
