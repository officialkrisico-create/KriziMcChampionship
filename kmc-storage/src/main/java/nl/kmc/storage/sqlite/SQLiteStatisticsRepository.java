package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredPointAudit;
import nl.kmc.storage.repository.StatisticsRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLiteStatisticsRepository extends AsyncBase implements StatisticsRepository {

    public SQLiteStatisticsRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<Void> recordPointAward(StoredPointAudit audit) {
        return asyncVoid(() -> insert(dataSource.get(), audit));
    }

    @Override
    public CompletableFuture<Void> recordPointAwardBatch(List<StoredPointAudit> audits) {
        return asyncVoid(() -> {
            Connection c = dataSource.get();
            c.setAutoCommit(false);
            try {
                for (StoredPointAudit a : audits) insert(c, a);
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
    public CompletableFuture<List<StoredPointAudit>> findAuditByPlayer(UUID uuid, int limit) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM point_audit WHERE player_uuid=? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                return mapAll(ps.executeQuery());
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredPointAudit>> findAuditByRound(int round) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM point_audit WHERE round=? ORDER BY id ASC")) {
                ps.setInt(1, round);
                return mapAll(ps.executeQuery());
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private void insert(Connection c, StoredPointAudit a) throws SQLException {
        try (var ps = c.prepareStatement("""
            INSERT INTO point_audit
                (player_uuid, player_name, team_id, game_id, reason, amount, round, timestamp)
            VALUES (?,?,?,?,?,?,?,?)
            """)) {
            ps.setString(1, a.playerUuid.toString());
            ps.setString(2, a.playerName);
            ps.setString(3, a.teamId);
            ps.setString(4, a.gameId);
            ps.setString(5, a.reason);
            ps.setInt(6,    a.amount);
            ps.setInt(7,    a.round);
            ps.setString(8, (a.timestamp != null ? a.timestamp : Instant.now()).toString());
            ps.executeUpdate();
        }
    }

    private List<StoredPointAudit> mapAll(ResultSet rs) throws SQLException {
        List<StoredPointAudit> list = new ArrayList<>();
        while (rs.next()) {
            StoredPointAudit a = new StoredPointAudit();
            a.playerUuid = UUID.fromString(rs.getString("player_uuid"));
            a.playerName = rs.getString("player_name");
            a.teamId     = rs.getString("team_id");
            a.gameId     = rs.getString("game_id");
            a.reason     = rs.getString("reason");
            a.amount     = rs.getInt("amount");
            a.round      = rs.getInt("round");
            a.timestamp  = Instant.parse(rs.getString("timestamp"));
            list.add(a);
        }
        return list;
    }
}
