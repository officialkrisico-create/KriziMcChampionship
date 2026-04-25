package nl.kmc.kmccore.database;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.HoFRecord;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.models.PointAward;
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
 *                      play_time_minutes, win_streak, best_win_streak, wins_per_game,
 *                      deaths)
 *   teams             (id, display_name, color, tag_color, points, wins)
 *   tournament_state  (key, value)
 *   point_awards      (id, player_uuid, team_id, reason, game_id, amount, round, timestamp)
 *   hof_records       (category, player_uuid, player_name, value, event_number, timestamp)
 *                       NEW — Hall of Fame. Persists across all resets.
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
            migrateAddDeathsColumn();
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
                wins_per_game      TEXT DEFAULT '',
                deaths             INTEGER DEFAULT 0
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
        String pointAwards = """
            CREATE TABLE IF NOT EXISTS point_awards (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid  TEXT NOT NULL,
                team_id      TEXT,
                reason       TEXT NOT NULL,
                game_id      TEXT,
                amount       INTEGER NOT NULL,
                round        INTEGER DEFAULT 1,
                timestamp    INTEGER NOT NULL
            );""";
        String hof = """
            CREATE TABLE IF NOT EXISTS hof_records (
                category      TEXT PRIMARY KEY,
                player_uuid   TEXT NOT NULL,
                player_name   TEXT NOT NULL,
                value         INTEGER NOT NULL,
                event_number  INTEGER DEFAULT 0,
                timestamp     INTEGER NOT NULL
            );""";
        String idxAwards = "CREATE INDEX IF NOT EXISTS idx_awards_player ON point_awards(player_uuid);";

        try (Statement st = connection.createStatement()) {
            st.execute(players);
            st.execute(teams);
            st.execute(tournament);
            st.execute(pointAwards);
            st.execute(hof);
            st.execute(idxAwards);
        }
    }

    private void migrateAddDeathsColumn() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(players)")) {
            boolean hasDeaths = false;
            while (rs.next()) {
                if ("deaths".equalsIgnoreCase(rs.getString("name"))) { hasDeaths = true; break; }
            }
            if (!hasDeaths) {
                try (Statement add = connection.createStatement()) {
                    add.execute("ALTER TABLE players ADD COLUMN deaths INTEGER DEFAULT 0");
                }
                plugin.getLogger().info("Added 'deaths' column to players table.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Migration check failed", e);
        }
    }

    // ----------------------------------------------------------------
    // Players
    // ----------------------------------------------------------------

    public PlayerData loadPlayer(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return readPlayerRow(rs);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load player failed", e);
        }
        return null;
    }

    private PlayerData readPlayerRow(ResultSet rs) throws SQLException {
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
        pd.setDeaths(safeInt(rs, "deaths"));
        return pd;
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
                games_played, play_time_minutes, win_streak, best_win_streak, wins_per_game, deaths)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, team_id=excluded.team_id, points=excluded.points,
                kills=excluded.kills, wins=excluded.wins, games_played=excluded.games_played,
                play_time_minutes=excluded.play_time_minutes, win_streak=excluded.win_streak,
                best_win_streak=excluded.best_win_streak, wins_per_game=excluded.wins_per_game,
                deaths=excluded.deaths;
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
            ps.setInt(12,   pd.getDeaths());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Save player failed", e);
        }
    }

    public List<PlayerData> loadAllPlayers() {
        List<PlayerData> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM players ORDER BY points DESC")) {
            while (rs.next()) list.add(readPlayerRow(rs));
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
                display_name=excluded.display_name, color=excluded.color,
                tag_color=excluded.tag_color, points=excluded.points, wins=excluded.wins;""";
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

    public void deleteTeam(String teamId) {
        if (teamId == null) return;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM teams WHERE id = ?")) {
            ps.setString(1, teamId);
            ps.executeUpdate();
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
    // Point awards
    // ----------------------------------------------------------------

    public void recordPointAward(PointAward award) {
        if (award == null) return;
        String sql = """
            INSERT INTO point_awards (player_uuid, team_id, reason, game_id, amount, round, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, award.getPlayerUuid().toString());
            ps.setString(2, award.getTeamId());
            ps.setString(3, award.getReason());
            ps.setString(4, award.getGameId());
            ps.setInt(5,    award.getAmount());
            ps.setInt(6,    award.getRound());
            ps.setLong(7,   award.getTimestamp());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Record point award failed", e);
        }
    }

    public List<PointAward> loadAwardsForPlayer(UUID uuid) {
        List<PointAward> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM point_awards WHERE player_uuid = ? ORDER BY timestamp ASC")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(readAwardRow(rs));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load player awards failed", e);
        }
        return list;
    }

    public List<PointAward> loadAwardsForTeam(String teamId) {
        List<PointAward> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM point_awards WHERE team_id = ? ORDER BY timestamp ASC")) {
            ps.setString(1, teamId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(readAwardRow(rs));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load team awards failed", e);
        }
        return list;
    }

    private PointAward readAwardRow(ResultSet rs) throws SQLException {
        return new PointAward(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("team_id"),
                rs.getString("reason"),
                rs.getString("game_id"),
                rs.getInt("amount"),
                rs.getInt("round"),
                rs.getLong("timestamp"));
    }

    public void clearAllAwards() {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM point_awards");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Clear awards failed", e);
        }
    }

    // ----------------------------------------------------------------
    // Hall of Fame  (NEW — survives all resets)
    // ----------------------------------------------------------------

    public Map<String, HoFRecord> loadAllHoFRecords() {
        Map<String, HoFRecord> map = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM hof_records")) {
            while (rs.next()) {
                map.put(rs.getString("category"), new HoFRecord(
                        rs.getString("category"),
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getLong("value"),
                        rs.getInt("event_number"),
                        rs.getLong("timestamp")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Load HoF records failed", e);
        }
        return map;
    }

    public void saveHoFRecord(HoFRecord rec) {
        if (rec == null) return;
        String sql = """
            INSERT INTO hof_records (category, player_uuid, player_name, value, event_number, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(category) DO UPDATE SET
                player_uuid=excluded.player_uuid,
                player_name=excluded.player_name,
                value=excluded.value,
                event_number=excluded.event_number,
                timestamp=excluded.timestamp;""";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rec.getCategory());
            ps.setString(2, rec.getPlayerUuid().toString());
            ps.setString(3, rec.getPlayerName());
            ps.setLong(4,   rec.getValue());
            ps.setInt(5,    rec.getEventNumber());
            ps.setLong(6,   rec.getTimestamp());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Save HoF record failed", e);
        }
    }

    /** Clears ONE category. Use clearAllHoFRecords() for everything. */
    public void clearHoFRecord(String category) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM hof_records WHERE category = ?")) {
            ps.setString(1, category);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Clear HoF record failed", e);
        }
    }

    public void clearAllHoFRecords() {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM hof_records");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Clear all HoF failed", e);
        }
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
     * Soft reset: zero player stats but preserve event_number and HoF.
     * Hard reset: also zero event_number, but HoF still persists.
     */
    public void resetAll(boolean fullWipe) {
        try (Statement st = connection.createStatement()) {
            if (fullWipe) {
                st.execute("DELETE FROM players");
                st.execute("DELETE FROM tournament_state");
                // NOTE: hof_records is intentionally NOT touched here.
            } else {
                st.execute("UPDATE players SET points = 0, kills = 0, wins = 0, " +
                           "win_streak = 0, games_played = 0, deaths = 0");
                st.execute("DELETE FROM tournament_state WHERE key NOT IN ('event_number')");
            }
            st.execute("UPDATE teams SET points = 0, wins = 0");
            st.execute("DELETE FROM point_awards");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Reset failed", e);
        }
    }

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
