package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredPlayer;
import nl.kmc.storage.repository.PlayerRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLitePlayerRepository extends AsyncBase implements PlayerRepository {

    public SQLitePlayerRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<Optional<StoredPlayer>> findById(UUID uuid) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                var rs = ps.executeQuery();
                if (rs.next()) return Optional.of(map(rs));
                return Optional.empty();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredPlayer>> findAll() {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement("SELECT * FROM players");
                 var rs = ps.executeQuery()) {
                List<StoredPlayer> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs));
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredPlayer>> findLeaderboard(int limit) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM players ORDER BY points DESC LIMIT ?")) {
                ps.setInt(1, limit);
                var rs = ps.executeQuery();
                List<StoredPlayer> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs));
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Void> save(StoredPlayer p) {
        return asyncVoid(() -> upsert(dataSource.get(), p));
    }

    @Override
    public CompletableFuture<Void> saveAll(List<StoredPlayer> players) {
        return asyncVoid(() -> {
            Connection c = dataSource.get();
            c.setAutoCommit(false);
            try {
                for (StoredPlayer p : players) upsert(c, p);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    @Override
    public CompletableFuture<Void> softReset(UUID uuid) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "UPDATE players SET points=0, kills=0, deaths=0, wins=0, games_played=0 WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> softResetAll() {
        return asyncVoid(() -> {
            try (var st = dataSource.get().createStatement()) {
                st.execute("UPDATE players SET points=0, kills=0, deaths=0, wins=0, games_played=0");
            }
        });
    }

    @Override
    public CompletableFuture<Void> hardReset(UUID uuid) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement("DELETE FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> hardResetAll() {
        return asyncVoid(() -> {
            try (var st = dataSource.get().createStatement()) {
                st.execute("DELETE FROM players");
            }
        });
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private void upsert(Connection c, StoredPlayer p) throws SQLException {
        try (var ps = c.prepareStatement("""
            INSERT INTO players
                (uuid, name, team_id, points, kills, deaths, wins, games_played,
                 play_time_minutes, win_streak, best_win_streak, wins_per_game)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, team_id=excluded.team_id,
                points=excluded.points, kills=excluded.kills,
                deaths=excluded.deaths, wins=excluded.wins,
                games_played=excluded.games_played,
                play_time_minutes=excluded.play_time_minutes,
                win_streak=excluded.win_streak,
                best_win_streak=excluded.best_win_streak,
                wins_per_game=excluded.wins_per_game
            """)) {
            ps.setString(1,  p.uuid.toString());
            ps.setString(2,  p.name);
            ps.setString(3,  p.teamId);
            ps.setInt(4,     p.points);
            ps.setInt(5,     p.kills);
            ps.setInt(6,     p.deaths);
            ps.setInt(7,     p.wins);
            ps.setInt(8,     p.gamesPlayed);
            ps.setLong(9,    p.playTimeMinutes);
            ps.setInt(10,    p.winStreak);
            ps.setInt(11,    p.bestWinStreak);
            ps.setString(12, serializeMap(p.winsPerGame));
            ps.executeUpdate();
        }
    }

    private StoredPlayer map(ResultSet rs) throws SQLException {
        StoredPlayer p = new StoredPlayer();
        p.uuid            = UUID.fromString(rs.getString("uuid"));
        p.name            = rs.getString("name");
        p.teamId          = rs.getString("team_id");
        p.points          = rs.getInt("points");
        p.kills           = rs.getInt("kills");
        p.deaths          = rs.getInt("deaths");
        p.wins            = rs.getInt("wins");
        p.gamesPlayed     = rs.getInt("games_played");
        p.playTimeMinutes = rs.getLong("play_time_minutes");
        p.winStreak       = rs.getInt("win_streak");
        p.bestWinStreak   = rs.getInt("best_win_streak");
        p.winsPerGame     = deserializeMap(rs.getString("wins_per_game"));
        return p;
    }

    /** Simple JSON-like map serializer to avoid pulling in Gson here. Format: {"key":val,...} */
    private String serializeMap(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return "{}";
        var sb = new StringBuilder("{");
        map.forEach((k, v) -> sb.append('"').append(k).append("\":").append(v).append(','));
        sb.setCharAt(sb.length() - 1, '}');
        return sb.toString();
    }

    private Map<String, Integer> deserializeMap(String json) {
        Map<String, Integer> map = new HashMap<>();
        if (json == null || json.equals("{}") || json.isBlank()) return map;
        // strip braces
        String inner = json.substring(1, json.length() - 1);
        for (String entry : inner.split(",")) {
            String[] kv = entry.split(":");
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "");
                map.put(key, Integer.parseInt(kv[1].trim()));
            }
        }
        return map;
    }
}
