package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredAchievement;
import nl.kmc.storage.model.StoredAchievementProgress;
import nl.kmc.storage.repository.AchievementRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLiteAchievementRepository extends AsyncBase implements AchievementRepository {

    public SQLiteAchievementRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<List<StoredAchievement>> findUnlockedByPlayer(UUID uuid) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM player_achievements WHERE player_uuid=? ORDER BY unlocked_at ASC")) {
                ps.setString(1, uuid.toString());
                List<StoredAchievement> list = new ArrayList<>();
                var rs = ps.executeQuery();
                while (rs.next()) {
                    StoredAchievement a = new StoredAchievement();
                    a.playerUuid    = UUID.fromString(rs.getString("player_uuid"));
                    a.achievementId = rs.getString("achievement_id");
                    a.eventNumber   = rs.getInt("event_number");
                    a.unlockedAt    = Instant.parse(rs.getString("unlocked_at"));
                    list.add(a);
                }
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Void> recordUnlock(StoredAchievement a) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement("""
                INSERT OR IGNORE INTO player_achievements
                    (player_uuid, achievement_id, event_number, unlocked_at)
                VALUES (?,?,?,?)
                """)) {
                ps.setString(1, a.playerUuid.toString());
                ps.setString(2, a.achievementId);
                ps.setInt(3,    a.eventNumber);
                ps.setString(4, (a.unlockedAt != null ? a.unlockedAt : Instant.now()).toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<StoredAchievementProgress>> findProgress(UUID uuid, String achievementId) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM achievement_progress WHERE player_uuid=? AND achievement_id=?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, achievementId);
                var rs = ps.executeQuery();
                if (rs.next()) return Optional.of(mapProgress(rs));
                return Optional.empty();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<List<StoredAchievementProgress>> findAllProgress(UUID uuid) {
        return async(() -> {
            try (var ps = dataSource.get().prepareStatement(
                    "SELECT * FROM achievement_progress WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                List<StoredAchievementProgress> list = new ArrayList<>();
                var rs = ps.executeQuery();
                while (rs.next()) list.add(mapProgress(rs));
                return list;
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Void> saveProgress(StoredAchievementProgress p) {
        return asyncVoid(() -> {
            try (var ps = dataSource.get().prepareStatement("""
                INSERT INTO achievement_progress (player_uuid, achievement_id, progress, target)
                VALUES (?,?,?,?)
                ON CONFLICT(player_uuid, achievement_id)
                DO UPDATE SET progress=excluded.progress, target=excluded.target
                """)) {
                ps.setString(1, p.playerUuid.toString());
                ps.setString(2, p.achievementId);
                ps.setInt(3,    p.progress);
                ps.setInt(4,    p.target);
                ps.executeUpdate();
            }
        });
    }

    private StoredAchievementProgress mapProgress(ResultSet rs) throws SQLException {
        StoredAchievementProgress p = new StoredAchievementProgress();
        p.playerUuid    = UUID.fromString(rs.getString("player_uuid"));
        p.achievementId = rs.getString("achievement_id");
        p.progress      = rs.getInt("progress");
        p.target        = rs.getInt("target");
        return p;
    }
}
