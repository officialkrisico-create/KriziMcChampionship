package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredTeam;
import nl.kmc.storage.repository.TeamRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLiteTeamRepository extends AsyncBase implements TeamRepository {

    public SQLiteTeamRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<Optional<StoredTeam>> findById(String teamId) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement("SELECT * FROM teams WHERE id=?")) {
                ps.setString(1, teamId);
                var rs = ps.executeQuery();
                if (rs.next()) return Optional.of(map(rs));
                return Optional.empty();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredTeam>> findAll() {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement("SELECT * FROM teams");
                 var rs = ps.executeQuery()) {
                List<StoredTeam> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs));
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredTeam>> findStandings() {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM teams ORDER BY points DESC");
                 var rs = ps.executeQuery()) {
                List<StoredTeam> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs));
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Void> save(StoredTeam t) {
        return asyncVoid(() -> upsert(dataSource.get(), t));
    }

    @Override
    public CompletableFuture<Void> saveAll(List<StoredTeam> teams) {
        return asyncVoid(() -> {
            Connection c = dataSource.get();
            c.setAutoCommit(false);
            try {
                for (StoredTeam t : teams) upsert(c, t);
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
    public CompletableFuture<Void> softResetAll() {
        return asyncVoid(() -> {
            try (var st = dataSource.get().createStatement()) {
                st.execute("UPDATE teams SET points=0, wins=0");
            }
        });
    }

    @Override
    public CompletableFuture<Void> hardResetAll() {
        return asyncVoid(() -> {
            try (var st = dataSource.get().createStatement()) {
                st.execute("DELETE FROM teams");
            }
        });
    }

    private void upsert(Connection c, StoredTeam t) throws SQLException {
        try (var ps = c.prepareStatement("""
            INSERT INTO teams (id, display_name, color, tag_color, points, wins)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                display_name=excluded.display_name, color=excluded.color,
                tag_color=excluded.tag_color, points=excluded.points, wins=excluded.wins
            """)) {
            ps.setString(1, t.id);
            ps.setString(2, t.displayName);
            ps.setString(3, t.color);
            ps.setString(4, t.tagColor);
            ps.setInt(5, t.points);
            ps.setInt(6, t.wins);
            ps.executeUpdate();
        }
    }

    private StoredTeam map(ResultSet rs) throws SQLException {
        StoredTeam t = new StoredTeam();
        t.id          = rs.getString("id");
        t.displayName = rs.getString("display_name");
        t.color       = rs.getString("color");
        t.tagColor    = rs.getString("tag_color");
        t.points      = rs.getInt("points");
        t.wins        = rs.getInt("wins");
        return t;
    }
}
