package nl.kmc.kmccore.history;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Persists tournament results to a {@code tournament_history} table so
 * players can look back at past events.
 *
 * <p>Each completed tournament gets:
 * <ul>
 *   <li>One row in {@code tournament_history} (id, ended_at, team_winner_id)</li>
 *   <li>One row per player in {@code tournament_player_results} —
 *       capturing their per-tournament points/kills/wins/rank</li>
 * </ul>
 *
 * <p>Stats reset is handled separately by TournamentManager.endTournament().
 * This manager just records the snapshot before reset happens.
 */
public class TournamentHistoryManager {

    private final KMCCore plugin;

    public TournamentHistoryManager(KMCCore plugin) {
        this.plugin = plugin;
        ensureTables();
    }

    private void ensureTables() {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tournament_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at BIGINT NOT NULL,
                    ended_at BIGINT NOT NULL,
                    team_winner_id VARCHAR(64),
                    team_winner_name VARCHAR(128),
                    team_winner_points INT,
                    total_rounds INT,
                    total_players INT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tournament_player_results (
                    tournament_id INTEGER NOT NULL,
                    uuid VARCHAR(36) NOT NULL,
                    name VARCHAR(64) NOT NULL,
                    rank INT NOT NULL,
                    points INT NOT NULL,
                    kills INT NOT NULL,
                    wins INT NOT NULL,
                    PRIMARY KEY (tournament_id, uuid)
                )""");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create history tables", e);
        }
    }

    /**
     * Record a completed tournament. Call this from
     * {@code TournamentManager.endTournament()} BEFORE stats are reset.
     *
     * @param startedAtMs   epoch ms when the tournament started
     * @param totalRounds   how many rounds were configured
     * @return the new tournament_history.id, or -1 on failure
     */
    public long recordTournamentEnd(long startedAtMs, int totalRounds) {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return -1;

        // 1. Determine winning team
        List<KMCTeam> teamsByPoints = new ArrayList<>(plugin.getTeamManager().getAllTeams());
        teamsByPoints.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        KMCTeam winner = teamsByPoints.isEmpty() ? null : teamsByPoints.get(0);

        // 2. Get sorted player list
        List<PlayerData> playersByPoints = plugin.getPlayerDataManager().getLeaderboard();

        long endedAt = System.currentTimeMillis();
        long historyId = -1;

        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tournament_history
                (started_at, ended_at, team_winner_id, team_winner_name,
                 team_winner_points, total_rounds, total_players)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, startedAtMs);
            ps.setLong(2, endedAt);
            ps.setString(3, winner != null ? winner.getId() : null);
            ps.setString(4, winner != null ? winner.getDisplayName() : null);
            ps.setInt(5, winner != null ? winner.getPoints() : 0);
            ps.setInt(6, totalRounds);
            ps.setInt(7, playersByPoints.size());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) historyId = keys.getLong(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to insert tournament_history", e);
            return -1;
        }

        // 3. Insert per-player results
        if (historyId > 0) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO tournament_player_results
                    (tournament_id, uuid, name, rank, points, kills, wins)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < playersByPoints.size(); i++) {
                    PlayerData pd = playersByPoints.get(i);
                    ps.setLong(1, historyId);
                    ps.setString(2, pd.getUuid().toString());
                    ps.setString(3, pd.getName());
                    ps.setInt(4, i + 1);
                    ps.setInt(5, pd.getPoints());
                    ps.setInt(6, pd.getKills());
                    ps.setInt(7, pd.getWins());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to insert tournament_player_results", e);
            }
        }

        // 4. Fire achievement hooks for everyone
        for (int i = 0; i < playersByPoints.size(); i++) {
            PlayerData pd = playersByPoints.get(i);
            int rank = i + 1;
            UUID uuid = pd.getUuid();
            boolean teamWon = winner != null && winner.getId().equals(pd.getTeamId());
            try { plugin.getAchievementManager().onTournamentEnd(uuid, rank, teamWon); }
            catch (Throwable ignored) {}
        }

        plugin.getLogger().info("Tournament recorded — id " + historyId
                + ", winner: " + (winner != null ? winner.getDisplayName() : "none"));
        return historyId;
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    /** Get all past tournaments, most-recent first. */
    public List<TournamentRecord> getRecentTournaments(int limit) {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return Collections.emptyList();
        List<TournamentRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, started_at, ended_at, team_winner_id, team_winner_name, "
                + "team_winner_points, total_rounds, total_players "
                + "FROM tournament_history ORDER BY ended_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TournamentRecord r = new TournamentRecord();
                    r.id              = rs.getLong("id");
                    r.startedAtMs     = rs.getLong("started_at");
                    r.endedAtMs       = rs.getLong("ended_at");
                    r.winnerTeamId    = rs.getString("team_winner_id");
                    r.winnerTeamName  = rs.getString("team_winner_name");
                    r.winnerPoints    = rs.getInt("team_winner_points");
                    r.totalRounds     = rs.getInt("total_rounds");
                    r.totalPlayers    = rs.getInt("total_players");
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query tournament_history", e);
        }
        return out;
    }

    /** Get a specific player's tournament history. */
    public List<PlayerResult> getPlayerHistory(UUID uuid, int limit) {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return Collections.emptyList();
        List<PlayerResult> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r.tournament_id, r.rank, r.points, r.kills, r.wins, "
                + "h.ended_at, h.team_winner_name "
                + "FROM tournament_player_results r "
                + "JOIN tournament_history h ON r.tournament_id = h.id "
                + "WHERE r.uuid = ? ORDER BY h.ended_at DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerResult pr = new PlayerResult();
                    pr.tournamentId = rs.getLong("tournament_id");
                    pr.rank          = rs.getInt("rank");
                    pr.points        = rs.getInt("points");
                    pr.kills         = rs.getInt("kills");
                    pr.wins          = rs.getInt("wins");
                    pr.endedAtMs     = rs.getLong("ended_at");
                    pr.winningTeam   = rs.getString("team_winner_name");
                    out.add(pr);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query player history", e);
        }
        return out;
    }

    // ----------------------------------------------------------------
    // Result types
    // ----------------------------------------------------------------

    public static class TournamentRecord {
        public long   id;
        public long   startedAtMs;
        public long   endedAtMs;
        public String winnerTeamId;
        public String winnerTeamName;
        public int    winnerPoints;
        public int    totalRounds;
        public int    totalPlayers;
    }

    public static class PlayerResult {
        public long   tournamentId;
        public int    rank;
        public int    points;
        public int    kills;
        public int    wins;
        public long   endedAtMs;
        public String winningTeam;
    }
}
