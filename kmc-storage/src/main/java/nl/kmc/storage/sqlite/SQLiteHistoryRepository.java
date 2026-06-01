package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredPlayerResult;
import nl.kmc.storage.model.StoredTournamentResult;
import nl.kmc.storage.repository.HistoryRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLiteHistoryRepository extends AsyncBase implements HistoryRepository {

    public SQLiteHistoryRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<Void> saveTournamentResult(StoredTournamentResult r) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement("""
                INSERT OR REPLACE INTO tournament_history
                    (event_number, tournament_name, winning_team_id, winning_team_name,
                     total_rounds, started_at, ended_at)
                VALUES (?,?,?,?,?,?,?)
                """)) {
                ps.setInt(1, r.eventNumber);
                ps.setString(2, r.tournamentName);
                ps.setString(3, r.winningTeamId);
                ps.setString(4, r.winningTeamName);
                ps.setInt(5, r.totalRounds);
                ps.setString(6, r.startedAt != null ? r.startedAt.toString() : Instant.now().toString());
                ps.setString(7, r.endedAt   != null ? r.endedAt.toString()   : Instant.now().toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> savePlayerResults(List<StoredPlayerResult> results) {
        return asyncVoid(() -> {
            Connection c = dataSource.get();
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("""
                INSERT INTO player_history
                    (event_number, player_uuid, player_name, team_id,
                     final_points, final_kills, final_deaths, final_wins,
                     placement, won_tournament)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
                for (StoredPlayerResult r : results) {
                    ps.setInt(1, r.eventNumber);
                    ps.setString(2, r.playerUuid.toString());
                    ps.setString(3, r.playerName);
                    ps.setString(4, r.teamId);
                    ps.setInt(5, r.finalPoints);
                    ps.setInt(6, r.finalKills);
                    ps.setInt(7, r.finalDeaths);
                    ps.setInt(8, r.finalWins);
                    ps.setInt(9, r.placement);
                    ps.setInt(10, r.wonTournament ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
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
    public CompletableFuture<List<StoredTournamentResult>> findAllTournaments() {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM tournament_history ORDER BY event_number ASC");
                 var rs = ps.executeQuery()) {
                return mapTournaments(rs);
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredPlayerResult>> findPlayerHistory(UUID uuid) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM player_history WHERE player_uuid=? ORDER BY event_number DESC")) {
                ps.setString(1, uuid.toString());
                return mapPlayerResults(ps.executeQuery());
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredTournamentResult>> findRecent(int limit) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM tournament_history ORDER BY event_number DESC LIMIT ?")) {
                ps.setInt(1, limit);
                return mapTournaments(ps.executeQuery());
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private List<StoredTournamentResult> mapTournaments(ResultSet rs) throws SQLException {
        List<StoredTournamentResult> list = new ArrayList<>();
        while (rs.next()) {
            StoredTournamentResult r = new StoredTournamentResult();
            r.eventNumber    = rs.getInt("event_number");
            r.tournamentName = rs.getString("tournament_name");
            r.winningTeamId  = rs.getString("winning_team_id");
            r.winningTeamName = rs.getString("winning_team_name");
            r.totalRounds    = rs.getInt("total_rounds");
            r.startedAt      = Instant.parse(rs.getString("started_at"));
            r.endedAt        = Instant.parse(rs.getString("ended_at"));
            list.add(r);
        }
        return list;
    }

    private List<StoredPlayerResult> mapPlayerResults(ResultSet rs) throws SQLException {
        List<StoredPlayerResult> list = new ArrayList<>();
        while (rs.next()) {
            StoredPlayerResult r = new StoredPlayerResult();
            r.eventNumber   = rs.getInt("event_number");
            r.playerUuid    = UUID.fromString(rs.getString("player_uuid"));
            r.playerName    = rs.getString("player_name");
            r.teamId        = rs.getString("team_id");
            r.finalPoints   = rs.getInt("final_points");
            r.finalKills    = rs.getInt("final_kills");
            r.finalDeaths   = rs.getInt("final_deaths");
            r.finalWins     = rs.getInt("final_wins");
            r.placement     = rs.getInt("placement");
            r.wonTournament = rs.getInt("won_tournament") == 1;
            list.add(r);
        }
        return list;
    }
}
