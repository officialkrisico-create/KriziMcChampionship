package nl.kmc.storage.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Manages a single SQLite connection with WAL mode and foreign-key enforcement.
 * All repositories share this connection — SQLite does not support a pool.
 */
public final class SQLiteDataSource {

    private static final Logger LOG = Logger.getLogger(SQLiteDataSource.class.getName());

    private final Path dbFile;
    private Connection connection;

    public SQLiteDataSource(Path dbFile) {
        this.dbFile = dbFile;
    }

    public void connect() throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        connection = DriverManager.getConnection(url);
        try (var st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA cache_size=-8000"); // 8 MB page cache
        }
        LOG.info("[KMC/Storage] Connected to " + dbFile.getFileName());
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                LOG.info("[KMC/Storage] Database connection closed.");
            } catch (SQLException e) {
                LOG.warning("[KMC/Storage] Error closing connection: " + e.getMessage());
            }
        }
    }

    /** Returns the shared connection. All callers are expected to be on the async executor. */
    public Connection get() {
        return connection;
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}
