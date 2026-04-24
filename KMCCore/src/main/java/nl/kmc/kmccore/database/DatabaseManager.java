package nl.kmc.kmccore.database;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.ChatColor;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * SQLite persistence for KMC Core.
 *
 * <p>Schema:
 * <pre>
 *   players           (uuid, name, team_id, points, kills, wins, games_played,
 *                      play_time_minutes, win_streak, best_win_streak, wins_per_game)
 *   teams             (id, display_name, color, tag_color, points, wins)
 *   tournament_state  (key, value)
 * </pre>
 */
public class DatabaseManager {

    private final KMCCore plugin;
    private Connection connection;

    public DatabaseManager(KMCCore plugin) { this.plugin = plugin; }

    public void connect() {
        try {
            File dbFile = new File(plugin.getDataFolder(),
                    plugin.getConfig().getString("database.file", "kmccore.db"));
            plugin.getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA foreign_keys=ON;");
            }
            createTables();
            plugin.getLogger().info("Database connected.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "DB connect failed", e);
        }
    }

    public void disconnect() {
        if (connection != null) {
            try { connection.close(); }
            catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "DB close", e); }
        }
    }

    private void createTables() throws SQLException {
        String players = """
            CREATE TABLE IF NOT EXISTS players (
                uuid               TEXT PRIMARY KEY,
                name               TEXT NOT NULL,
                team_id            TEXT,
                points             INTEGER DEFAULT 0,
                kills              INTEGER DEFAULT 0,
                wins               INTEGER DEFAULT 0,
                games_played       INTEGER DEFAULT 0,
                play_time_minutes  INTEGER DEFAULT 0,
                win_streak         INTEGER DEFAULT 0,
                best_win_streak    INTEGER DEFAULT 0,
                wins_per_game      TEXT DEFAULT ''
            );""";
        String teams = """
            CREATE TABLE IF NOT EXISTS teams (
                id           TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                color        TEXT NOT NULL,
                tag_color    TEXT NOT NULL,
                points       INTEGER DEFAULT 0,
                wins         INTEGER DEFAULT 0
            );""";
        String tournament = """
            CREATE TABLE IF NOT EXISTS tournament_state (
                key   TEXT PRIMARY KEY,
                value TEXT
            );""";

        try (Statement st = connection.createStatement()) {
            st.execute(players);
            st.execute(teams);
            st.execute(tournament);
        }
    }

    // ----------------------------------------------------------------
    // Players
    // ----------------------------------------------------------------

    public PlayerData loadPlayer(UUID uuid) {
        String sql = "SELECT * FROM players WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                PlayerData pd = new PlayerData(uuid, rs.getString("name"));
                pd.setTeamId(rs.getString("team_id"));
                pd.setPoints(rs.getInt("points"));
                pd.setKills(rs.getInt("kills"));
                pd.setWins(rs.getInt("wins"));
                pd.setGamesPlayed(safeInt(rs, "games_played"));
                pd.setTotalPlayTimeMinutes(safeInt(rs, "play_time_minutes"));
                pd.setWinStreak(safeInt(rs, "win_streak"));
                pd.setBestWinStreak(safeInt(rs, "best_win_streak"));
                pd.setWinsPerGame(deserializeWinsPerGame(safeStr(rs, "wins_per_game")));
                return pd;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load player failed", e);
        }
        return null;
    }

    private int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (SQLException e) { return 0; }
    }
    private String safeStr(ResultSet rs, String col) {
        try { return rs.getString(col); } catch (SQLException e) { return ""; }
    }

    public void savePlayer(PlayerData pd) {
        String sql = """
            INSERT INTO players (uuid, name, team_id, points, kills, wins,
                games_played, play_time_minutes, win_streak, best_win_streak, wins_per_game)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name               = excluded.name,
                team_id            = excluded.team_id,
                points             = excluded.points,
                kills              = excluded.kills,
                wins               = excluded.wins,
                games_played       = excluded.games_played,
                play_time_minutes  = excluded.play_time_minutes,
                win_streak         = excluded.win_streak,
                best_win_streak    = excluded.best_win_streak,
                wins_per_game      = excluded.wins_per_game;
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pd.getUuid().toString());
            ps.setString(2, pd.getName());
            ps.setString(3, pd.getTeamId());
            ps.setInt(4,    pd.getPoints());
            ps.setInt(5,    pd.getKills());
            ps.setInt(6,    pd.getWins());
            ps.setInt(7,    pd.getGamesPlayed());
            ps.setInt(8,    pd.getTotalPlayTimeMinutes());
            ps.setInt(9,    pd.getWinStreak());
            ps.setInt(10,   pd.getBestWinStreak());
            ps.setString(11, serializeWinsPerGame(pd.getWinsPerGame()));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Save player failed", e);
        }
    }

    public List<PlayerData> loadAllPlayers() {
        List<PlayerData> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM players ORDER BY points DESC")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerData pd = new PlayerData(uuid, rs.getString("name"));
                pd.setTeamId(rs.getString("team_id"));
                pd.setPoints(rs.getInt("points"));
                pd.setKills(rs.getInt("kills"));
                pd.setWins(rs.getInt("wins"));
                pd.setGamesPlayed(safeInt(rs, "games_played"));
                pd.setTotalPlayTimeMinutes(safeInt(rs, "play_time_minutes"));
                pd.setWinStreak(safeInt(rs, "win_streak"));
                pd.setBestWinStreak(safeInt(rs, "best_win_streak"));
                pd.setWinsPerGame(deserializeWinsPerGame(safeStr(rs, "wins_per_game")));
                list.add(pd);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load all players failed", e);
        }
        return list;
    }

    // ----------------------------------------------------------------
    // Teams
    // ----------------------------------------------------------------

    public void saveTeam(KMCTeam team) {
        String sql = """
            INSERT INTO teams (id, display_name, color, tag_color, points, wins)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                display_name = excluded.display_name,
                color        = excluded.color,
                tag_color    = excluded.tag_color,
                points       = excluded.points,
                wins         = excluded.wins;""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, team.getId());
            ps.setString(2, team.getDisplayName());
            ps.setString(3, team.getColor().name());
            ps.setString(4, team.getTagColor());
            ps.setInt(5, team.getPoints());
            ps.setInt(6, team.getWins());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Save team failed", e);
        }
    }

    /**
     * Deletes a single team from the database.
     * Called by TeamManager.deleteTeam(id) for the /kmcteam delete command.
     *
     * @param teamId the id of the team to remove (case-sensitive)
     */
    public void deleteTeam(String teamId) {
        if (teamId == null) return;
        String sql = "DELETE FROM teams WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, teamId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                plugin.getLogger().info("Deleted team '" + teamId + "' from database.");
            }
            // Also clear team assignments on any players that referenced this team
            try (PreparedStatement ps2 = connection.prepareStatement(
                    "UPDATE players SET team_id = NULL WHERE team_id = ?")) {
                ps2.setString(1, teamId);
                ps2.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Delete team failed", e);
        }
    }

    public Map<String, KMCTeam> loadAllTeams() {
        Map<String, KMCTeam> map = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM teams ORDER BY points DESC")) {
            while (rs.next()) {
                String id = rs.getString("id");
                KMCTeam t = new KMCTeam(id, rs.getString("display_name"),
                        ChatColor.valueOf(rs.getString("color")), rs.getString("tag_color"));
                t.setPoints(rs.getInt("points"));
                t.setWins(rs.getInt("wins"));
                map.put(id, t);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load teams failed", e);
        }
        return map;
    }

    // ----------------------------------------------------------------
    // Tournament state
    // ----------------------------------------------------------------

    public void setTournamentValue(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tournament_state (key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value;")) {
            ps.setString(1, key); ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "set tournament", e); }
    }

    public String getTournamentValue(String key, String def) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM tournament_state WHERE key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "get tournament", e); }
        return def;
    }

    /**
     * Full reset — called by the tournament-end reset.
     * Wipes points, kills, wins, streaks, games played, and play time.
     * Preserves lifetime "bestWinStreak" and "winsPerGame" if configured.
     *
     * @param fullWipe if true, wipes EVERYTHING. Otherwise keeps lifetime stats.
     */
    public void resetAll(boolean fullWipe) {
        try (Statement st = connection.createStatement()) {
            if (fullWipe) {
                st.execute("DELETE FROM players");
                st.execute("DELETE FROM tournament_state");
            } else {
                st.execute("UPDATE players SET points = 0, kills = 0, wins = 0, " +
                           "win_streak = 0, games_played = 0");
                st.execute("DELETE FROM tournament_state");
            }
            st.execute("UPDATE teams SET points = 0, wins = 0");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Reset failed", e);
        }
    }

    /** Legacy alias — calls resetAll(true). */
    public void resetAll() { resetAll(true); }

    // ----------------------------------------------------------------
    // Serialisation
    // ----------------------------------------------------------------

    private String serializeWinsPerGame(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("|");
            sb.append(e.getKey()).append(":").append(e.getValue());
        }
        return sb.toString();
    }

    private Map<String, Integer> deserializeWinsPerGame(String raw) {
        Map<String, Integer> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String part : raw.split("\\|")) {
            String[] kv = part.split(":");
            if (kv.length == 2) {
                try { map.put(kv[0], Integer.parseInt(kv[1])); }
                catch (NumberFormatException ignored) {}
            }
        }
        return map;
    }
}
