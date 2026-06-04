package nl.kmc.storage.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class SQLiteMigrationRunnerTest {

    @TempDir Path tempDir;
    private SQLiteDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new SQLiteDataSource(tempDir.resolve("test.db"));
        dataSource.connect();
    }

    @AfterEach
    void tearDown() { dataSource.disconnect(); }

    @Test
    void migration_creates_all_core_tables() throws SQLException {
        new SQLiteMigrationRunner(dataSource).run();
        assertTableExists("players");
        assertTableExists("teams");
        assertTableExists("tournament_state");
        assertTableExists("point_audit");
        assertTableExists("tournament_history");
        assertTableExists("player_history");
        assertTableExists("player_achievements");
        assertTableExists("achievement_progress");
        assertTableExists("snapshots");
    }

    @Test
    void migration_is_idempotent() throws SQLException {
        // Running twice must not throw
        assertDoesNotThrow(() -> {
            new SQLiteMigrationRunner(dataSource).run();
            new SQLiteMigrationRunner(dataSource).run();
        });
    }

    @Test
    void schema_version_advances_to_5() throws SQLException {
        new SQLiteMigrationRunner(dataSource).run();
        Connection c = dataSource.get();
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT version FROM schema_version")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
        }
    }

    private void assertTableExists(String tableName) throws SQLException {
        DatabaseMetaData meta = dataSource.get().getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            assertTrue(rs.next(), "Table '" + tableName + "' should exist");
        }
    }
}
