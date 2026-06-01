package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredSnapshot;
import nl.kmc.storage.repository.SnapshotRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLiteSnapshotRepository extends AsyncBase implements SnapshotRepository {

    public SQLiteSnapshotRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<Void> save(StoredSnapshot s) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement("""
                INSERT INTO snapshots (label, payload, phase, created_at) VALUES (?,?,?,?)
                ON CONFLICT(label) DO UPDATE SET
                    payload=excluded.payload, phase=excluded.phase, created_at=excluded.created_at
                """)) {
                ps.setString(1, s.label);
                ps.setString(2, s.payload);
                ps.setString(3, s.phase);
                ps.setString(4, (s.createdAt != null ? s.createdAt : Instant.now()).toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<StoredSnapshot>> findByLabel(String label) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM snapshots WHERE label=?")) {
                ps.setString(1, label);
                var rs = ps.executeQuery();
                if (rs.next()) return Optional.of(map(rs));
                return Optional.empty();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Optional<StoredSnapshot>> findLatest() {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM snapshots ORDER BY created_at DESC LIMIT 1");
                 var rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
                return Optional.empty();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredSnapshot>> findAll() {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM snapshots ORDER BY created_at DESC");
                 var rs = ps.executeQuery()) {
                List<StoredSnapshot> list = new ArrayList<>();
                while (rs.next()) list.add(map(rs));
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Void> delete(String label) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "DELETE FROM snapshots WHERE label=?")) {
                ps.setString(1, label);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> pruneOldest(int keepCount) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement("""
                DELETE FROM snapshots WHERE label NOT IN (
                    SELECT label FROM snapshots ORDER BY created_at DESC LIMIT ?
                )""")) {
                ps.setInt(1, keepCount);
                ps.executeUpdate();
            }
        });
    }

    private StoredSnapshot map(ResultSet rs) throws SQLException {
        StoredSnapshot s = new StoredSnapshot();
        s.label     = rs.getString("label");
        s.payload   = rs.getString("payload");
        s.phase     = rs.getString("phase");
        s.createdAt = Instant.parse(rs.getString("created_at"));
        return s;
    }
}
