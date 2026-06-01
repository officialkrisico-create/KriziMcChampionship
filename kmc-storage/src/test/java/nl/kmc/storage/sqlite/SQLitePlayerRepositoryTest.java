package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SQLitePlayerRepositoryTest {

    @TempDir Path tempDir;
    private SQLiteDataSource         dataSource;
    private SQLitePlayerRepository   repo;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new SQLiteDataSource(tempDir.resolve("test.db"));
        dataSource.connect();
        new SQLiteMigrationRunner(dataSource).run();
        repo = new SQLitePlayerRepository(dataSource, Executors.newSingleThreadExecutor());
    }

    @AfterEach
    void tearDown() { dataSource.disconnect(); }

    @Test
    void save_and_find_player() throws Exception {
        UUID uuid = UUID.randomUUID();
        StoredPlayer p = new StoredPlayer(uuid, "TestPlayer");
        p.points = 150; p.kills = 5; p.teamId = "rood";

        repo.save(p).get();
        Optional<StoredPlayer> found = repo.findById(uuid).get();

        assertTrue(found.isPresent());
        assertEquals("TestPlayer", found.get().name);
        assertEquals(150, found.get().points);
        assertEquals(5, found.get().kills);
        assertEquals("rood", found.get().teamId);
    }

    @Test
    void upsert_updates_existing_player() throws Exception {
        UUID uuid = UUID.randomUUID();
        StoredPlayer p = new StoredPlayer(uuid, "Player");
        p.points = 100;
        repo.save(p).get();

        p.points = 300;
        p.kills = 10;
        repo.save(p).get();

        Optional<StoredPlayer> found = repo.findById(uuid).get();
        assertTrue(found.isPresent());
        assertEquals(300, found.get().points);
        assertEquals(10, found.get().kills);
    }

    @Test
    void soft_reset_zeroes_tournament_stats() throws Exception {
        UUID uuid = UUID.randomUUID();
        StoredPlayer p = new StoredPlayer(uuid, "Player");
        p.points = 500; p.kills = 20; p.deaths = 5;
        p.playTimeMinutes = 60; p.bestWinStreak = 3;
        repo.save(p).get();

        repo.softReset(uuid).get();

        Optional<StoredPlayer> found = repo.findById(uuid).get();
        assertTrue(found.isPresent());
        assertEquals(0, found.get().points);
        assertEquals(0, found.get().kills);
        // lifetime stats preserved
        assertEquals(60, found.get().playTimeMinutes);
        assertEquals(3, found.get().bestWinStreak);
    }

    @Test
    void wins_per_game_roundtrips() throws Exception {
        UUID uuid = UUID.randomUUID();
        StoredPlayer p = new StoredPlayer(uuid, "Player");
        p.winsPerGame.put("skywars", 3);
        p.winsPerGame.put("bingo", 1);
        repo.save(p).get();

        Optional<StoredPlayer> found = repo.findById(uuid).get();
        assertTrue(found.isPresent());
        assertEquals(3, found.get().winsPerGame.getOrDefault("skywars", 0));
        assertEquals(1, found.get().winsPerGame.getOrDefault("bingo", 0));
    }

    @Test
    void leaderboard_is_sorted_descending() throws Exception {
        for (int i = 1; i <= 5; i++) {
            StoredPlayer p = new StoredPlayer(UUID.randomUUID(), "Player" + i);
            p.points = i * 100;
            repo.save(p).get();
        }
        var lb = repo.findLeaderboard(3).get();
        assertEquals(3, lb.size());
        assertEquals(500, lb.get(0).points);
        assertEquals(400, lb.get(1).points);
        assertEquals(300, lb.get(2).points);
    }

    @Test
    void find_by_id_returns_empty_for_unknown_uuid() throws Exception {
        Optional<StoredPlayer> found = repo.findById(UUID.randomUUID()).get();
        assertTrue(found.isEmpty());
    }
}
