package nl.kmc.kmccore.api;

import nl.kmc.core.api.*;
import nl.kmc.core.domain.AchievementDefinition;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.core.domain.PointAward;
import nl.kmc.core.domain.TournamentPhase;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;

import java.util.*;

/**
 * V1-backed implementation of the V2 {@link KMCApi}.
 *
 * <p><b>The single source of truth.</b> Game plugins call
 * {@code KMCApiProvider.get().points()...} etc. This routes every one of those
 * calls into the V1 KMCCore managers ({@code PointsManager}, {@code TeamManager},
 * {@code PlayerDataManager}, {@code TournamentManager}) so that game writes and
 * the lobby read from the <b>same</b> store ({@code kmccore.db}).
 *
 * <p>This replaces the old split where games wrote to the V2 services
 * ({@code kmc_v2.db}) while the lobby read V1 — the cause of the points
 * "split-brain". KMCCore registers this via {@code KMCApiProvider.set(...)};
 * {@code KMCCoreV2} no longer registers its own.
 */
public final class V1KMCApi implements nl.kmc.core.api.KMCApi {

    private final KMCCore plugin;
    private final TeamApi        teamApi;
    private final PointsApi      pointsApi;
    private final GameApi        gameApi;
    private final StatsApi       statsApi;

    public V1KMCApi(KMCCore plugin) {
        this.plugin    = plugin;
        this.teamApi   = new V1TeamApi();
        this.pointsApi = new V1PointsApi();
        this.gameApi   = new V1GameApi();
        this.statsApi  = new V1StatsApi();
    }

    @Override public TeamApi        teams()        { return teamApi; }
    @Override public PointsApi      points()       { return pointsApi; }
    @Override public GameApi        games()        { return gameApi; }
    @Override public StatsApi       stats()        { return statsApi; }
    @Override public AchievementApi achievements() { return new V1AchievementApi(); }

    // ── PlayerData (V1) → KMCPlayer (V2) view ─────────────────────────────────

    private static KMCPlayer toKMCPlayer(PlayerData pd) {
        if (pd == null) return null;
        KMCPlayer p = new KMCPlayer(pd.getUuid(), pd.getName());
        p.setTeamId(pd.getTeamId());
        p.setPoints(pd.getPoints());
        p.setKills(pd.getKills());
        p.setDeaths(pd.getDeaths());
        p.setWins(pd.getWins());
        p.setGamesPlayed(pd.getGamesPlayed());
        p.setPlayTimeMinutes(pd.getTotalPlayTimeMinutes());
        p.setWinStreak(pd.getWinStreak());
        p.setBestWinStreak(pd.getBestWinStreak());
        p.setTeamChatEnabled(pd.isTeamChatEnabled());
        pd.getWinsPerGame().forEach(p::putWinsPerGame);
        return p;
    }

    // ── Points ────────────────────────────────────────────────────────────────

    private final class V1PointsApi implements PointsApi {
        @Override
        public void givePoints(UUID uuid, int amount, PointAward.Reason reason, String gameId) {
            // Ensure the player is loaded so the award isn't dropped.
            plugin.getPlayerDataManager().getOrCreate(uuid, null);
            var pm = plugin.getPointsManager();
            boolean useMultiplier = reason != PointAward.Reason.KILL || pm.killsUseMultiplier();
            int finalAmount = useMultiplier
                    ? (int) Math.round(amount * pm.getCurrentMultiplier())
                    : amount;
            pm.awardPlayerPoints(uuid, finalAmount); // credits player + team
        }

        @Override
        public void giveTeamPoints(String teamId, int amount, PointAward.Reason reason, String gameId) {
            int finalAmount = (int) Math.round(amount * plugin.getPointsManager().getCurrentMultiplier());
            plugin.getPointsManager().addTeamPoints(teamId, finalAmount);
        }

        @Override
        public void awardPlayerPlacement(UUID uuid, int placement, int totalPlayers, String gameId) {
            plugin.getPlayerDataManager().getOrCreate(uuid, null);
            plugin.getPointsManager().awardPlayerPlacement(uuid, placement);
        }

        @Override
        public void awardTeamPlacement(String teamId, int placement, String gameId) {
            plugin.getPointsManager().awardTeamPlacement(teamId, placement);
        }

        @Override public double getCurrentMultiplier() { return plugin.getPointsManager().getCurrentMultiplier(); }

        @Override public void setPoints(UUID uuid, int points)        { plugin.getPointsManager().setPlayerPoints(uuid, points); }
        @Override public void setTeamPoints(String teamId, int points) { plugin.getPointsManager().setTeamPoints(teamId, points); }

        @Override
        public void adjustPoints(UUID uuid, int delta, String reason) {
            if (delta >= 0) plugin.getPointsManager().addPlayerPoints(uuid, delta);
            else            plugin.getPointsManager().removePlayerPoints(uuid, -delta);
        }

        @Override
        public void adjustTeamPoints(String teamId, int delta, String reason) {
            if (delta >= 0) plugin.getPointsManager().addTeamPoints(teamId, delta);
            else            plugin.getPointsManager().removeTeamPoints(teamId, -delta);
        }
    }

    // ── Teams ─────────────────────────────────────────────────────────────────

    private final class V1TeamApi implements TeamApi {
        @Override public Optional<KMCTeam> getTeam(String teamId)        { return Optional.ofNullable(plugin.getTeamManager().getTeam(teamId)); }
        @Override public Optional<KMCTeam> getTeamByPlayer(UUID uuid)    { return Optional.ofNullable(plugin.getTeamManager().getTeamByPlayer(uuid)); }
        @Override public List<KMCTeam>     getAllTeams()                 { return new ArrayList<>(plugin.getTeamManager().getAllTeams()); }
        @Override public List<KMCTeam>     getStandings()               { return plugin.getTeamManager().getTeamsSortedByPoints(); }

        @Override public Optional<KMCPlayer> getPlayer(UUID uuid)        { return Optional.ofNullable(toKMCPlayer(plugin.getPlayerDataManager().get(uuid))); }
        @Override public KMCPlayer getOrCreatePlayer(UUID uuid, String name) { return toKMCPlayer(plugin.getPlayerDataManager().getOrCreate(uuid, name)); }
        @Override public List<KMCPlayer> getAllPlayers() {
            return plugin.getPlayerDataManager().getLeaderboard().stream()
                    .map(V1KMCApi::toKMCPlayer).toList();
        }
    }

    // ── Game lifecycle ──────────────────────────────────────────────────────────

    private final class V1GameApi implements GameApi {
        private String scoreboardOwner;

        @Override public boolean acquireScoreboard(String gameId) {
            // Delegate to the lobby KMCApi so the lobby scoreboard + leaderboard
            // bossbar are actually suppressed while a game owns the board.
            boolean ok = plugin.getApi().acquireScoreboard(gameId);
            if (ok) scoreboardOwner = gameId;
            return ok;
        }
        @Override public void releaseScoreboard(String gameId) {
            if (gameId.equals(scoreboardOwner)) scoreboardOwner = null;
            plugin.getScoreboardManager().clearGameBoard(gameId);
            plugin.getApi().releaseScoreboard(gameId);
        }
        @Override public void setScoreboard(String gameId, nl.kmc.core.api.GameScoreboard board) {
            plugin.getScoreboardManager().setGameBoard(gameId, board);
        }
        @Override public void clearScoreboard(String gameId) {
            plugin.getScoreboardManager().clearGameBoard(gameId);
        }
        @Override public boolean isScoreboardOwnedBy(String gameId) { return gameId.equals(scoreboardOwner); }
        @Override public Optional<String> getScoreboardOwner()      { return Optional.ofNullable(scoreboardOwner); }

        @Override public void signalGameEnd(String gameId, String winnerDescription) { releaseScoreboard(gameId); }

        @Override
        public void recordGameParticipation(UUID playerUuid, String playerName, String gameId, boolean won) {
            plugin.getApi().recordGameParticipation(playerUuid, playerName, gameId, won);
        }

        @Override public Optional<GameRegistration> getActiveGame() { return Optional.empty(); }
        @Override public int             getCurrentRound()          { return plugin.getTournamentManager().getCurrentRound(); }
        @Override public boolean         isTournamentActive()       { return plugin.getTournamentManager().isActive(); }
        @Override public TournamentPhase getCurrentPhase() {
            return plugin.getGameManager().isGameActive()
                    ? TournamentPhase.GAME_ACTIVE : TournamentPhase.WAITING;
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────────

    private final class V1StatsApi implements StatsApi {
        @Override public List<KMCPlayer> getPlayerLeaderboard(int limit) {
            return plugin.getPlayerDataManager().getLeaderboard().stream()
                    .limit(limit).map(V1KMCApi::toKMCPlayer).toList();
        }
        @Override public List<KMCTeam> getTeamLeaderboard(int limit) {
            return plugin.getTeamManager().getTeamsSortedByPoints().stream().limit(limit).toList();
        }
        @Override public int getKills(UUID uuid)       { PlayerData p = plugin.getPlayerDataManager().get(uuid); return p != null ? p.getKills() : 0; }
        @Override public int getDeaths(UUID uuid)      { PlayerData p = plugin.getPlayerDataManager().get(uuid); return p != null ? p.getDeaths() : 0; }
        @Override public int getGamesPlayed(UUID uuid) { PlayerData p = plugin.getPlayerDataManager().get(uuid); return p != null ? p.getGamesPlayed() : 0; }
        @Override public int getPoints(UUID uuid)      { PlayerData p = plugin.getPlayerDataManager().get(uuid); return p != null ? p.getPoints() : 0; }
        @Override public int getTeamPoints(String teamId) { KMCTeam t = plugin.getTeamManager().getTeam(teamId); return t != null ? t.getPoints() : 0; }
        @Override public int getPlayerRank(UUID uuid) {
            List<PlayerData> lb = plugin.getPlayerDataManager().getLeaderboard();
            for (int i = 0; i < lb.size(); i++) if (lb.get(i).getUuid().equals(uuid)) return i + 1;
            return -1;
        }
        @Override public int getTeamRank(String teamId) {
            List<KMCTeam> s = plugin.getTeamManager().getTeamsSortedByPoints();
            for (int i = 0; i < s.size(); i++) if (s.get(i).getId().equals(teamId)) return i + 1;
            return -1;
        }
    }

    // ── Achievements ─────────────────────────────────────────────────────────────
    // Delegates to the V2 AchievementService if the V2 achievement system is active,
    // otherwise a no-op (V1 achievements are handled by AchievementManager directly).

    private final class V1AchievementApi implements AchievementApi {
        private AchievementApi backing() {
            var svc = plugin.getAchievementServiceV2();
            return (svc instanceof AchievementApi a) ? a : null;
        }
        @Override public void grant(UUID uuid, String id)  { var b = backing(); if (b != null) b.grant(uuid, id); }
        @Override public void revoke(UUID uuid, String id) { var b = backing(); if (b != null) b.revoke(uuid, id); }
        @Override public boolean has(UUID uuid, String id) { var b = backing(); return b != null && b.has(uuid, id); }
        @Override public Set<String> getUnlocked(UUID uuid){ var b = backing(); return b != null ? b.getUnlocked(uuid) : Set.of(); }
        @Override public int getProgress(UUID uuid, String id) { var b = backing(); return b != null ? b.getProgress(uuid, id) : 0; }
        @Override public Collection<AchievementDefinition> getAll() { var b = backing(); return b != null ? b.getAll() : List.of(); }
        @Override public AchievementDefinition get(String id) { var b = backing(); return b != null ? b.get(id) : null; }
        @Override public void reload() { var b = backing(); if (b != null) b.reload(); }
    }
}
