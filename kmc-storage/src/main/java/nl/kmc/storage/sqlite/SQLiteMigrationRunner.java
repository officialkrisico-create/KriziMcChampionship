package nl.kmc.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Applies schema migrations in order. Each migration is idempotent via
 * CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS patterns.
 * New columns are added in numbered migrations — never alter existing ones.
 */
public final class SQLiteMigrationRunner {

    private static final Logger LOG = Logger.getLogger(SQLiteMigrationRunner.class.getName());

    private final SQLiteDataSource dataSource;

    public SQLiteMigrationRunner(SQLiteDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void run() throws SQLException {
        Connection c = dataSource.get();
        createSchemaVersionTable(c);
        int version = getSchemaVersion(c);
        LOG.info("[KMC/Storage] Schema version: " + version);

        if (version < 1) { migration1(c); setSchemaVersion(c, 1); }
        if (version < 2) { migration2(c); setSchemaVersion(c, 2); }
        if (version < 3) { migration3(c); setSchemaVersion(c, 3); }
        if (version < 4) { migration4(c); setSchemaVersion(c, 4); }
        if (version < 5) { migration5(c); setSchemaVersion(c, 5); }

        LOG.info("[KMC/Storage] Migrations complete (version " + getSchemaVersion(c) + ").");
    }

    private void createSchemaVersionTable(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL
                )""");
            var rs = st.executeQuery("SELECT COUNT(*) FROM schema_version");
            if (rs.getInt(1) == 0) {
                st.execute("INSERT INTO schema_version VALUES (0)");
            }
        }
    }

    private int getSchemaVersion(Connection c) throws SQLException {
        try (var st = c.createStatement();
             var rs = st.executeQuery("SELECT version FROM schema_version")) {
            return rs.getInt(1);
        }
    }

    private void setSchemaVersion(Connection c, int v) throws SQLException {
        try (var ps = c.prepareStatement("UPDATE schema_version SET version = ?")) {
            ps.setInt(1, v);
            ps.executeUpdate();
        }
    }

    // ── Migration 1: core tables ──────────────────────────────────────────────

    private void migration1(Connection c) throws SQLException {
        LOG.info("[KMC/Storage] Running migration 1: core tables");
        try (var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid              TEXT PRIMARY KEY,
                    name              TEXT NOT NULL,
                    team_id           TEXT,
                    points            INTEGER NOT NULL DEFAULT 0,
                    kills             INTEGER NOT NULL DEFAULT 0,
                    deaths            INTEGER NOT NULL DEFAULT 0,
                    wins              INTEGER NOT NULL DEFAULT 0,
                    games_played      INTEGER NOT NULL DEFAULT 0,
                    play_time_minutes INTEGER NOT NULL DEFAULT 0,
                    win_streak        INTEGER NOT NULL DEFAULT 0,
                    best_win_streak   INTEGER NOT NULL DEFAULT 0,
                    wins_per_game     TEXT    NOT NULL DEFAULT '{}'
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS teams (
                    id           TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    color        TEXT NOT NULL,
                    tag_color    TEXT NOT NULL,
                    points       INTEGER NOT NULL DEFAULT 0,
                    wins         INTEGER NOT NULL DEFAULT 0
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS tournament_state (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS point_audit (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid  TEXT    NOT NULL,
                    player_name  TEXT    NOT NULL,
                    team_id      TEXT,
                    game_id      TEXT,
                    reason       TEXT    NOT NULL,
                    amount       INTEGER NOT NULL,
                    round        INTEGER NOT NULL,
                    timestamp    TEXT    NOT NULL
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS tournament_history (
                    event_number    INTEGER PRIMARY KEY,
                    tournament_name TEXT    NOT NULL,
                    winning_team_id TEXT,
                    winning_team_name TEXT,
                    total_rounds    INTEGER NOT NULL,
                    started_at      TEXT    NOT NULL,
                    ended_at        TEXT    NOT NULL
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS player_history (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_number   INTEGER NOT NULL,
                    player_uuid    TEXT    NOT NULL,
                    player_name    TEXT    NOT NULL,
                    team_id        TEXT,
                    final_points   INTEGER NOT NULL DEFAULT 0,
                    final_kills    INTEGER NOT NULL DEFAULT 0,
                    final_deaths   INTEGER NOT NULL DEFAULT 0,
                    final_wins     INTEGER NOT NULL DEFAULT 0,
                    placement      INTEGER NOT NULL DEFAULT 0,
                    won_tournament INTEGER NOT NULL DEFAULT 0
                )""");
        }
    }

    // ── Migration 2: achievements + snapshots ────────────────────────────────

    private void migration2(Connection c) throws SQLException {
        LOG.info("[KMC/Storage] Running migration 2: achievements + snapshots");
        try (var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_achievements (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid    TEXT    NOT NULL,
                    achievement_id TEXT    NOT NULL,
                    event_number   INTEGER NOT NULL,
                    unlocked_at    TEXT    NOT NULL,
                    UNIQUE(player_uuid, achievement_id)
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS achievement_progress (
                    player_uuid    TEXT    NOT NULL,
                    achievement_id TEXT    NOT NULL,
                    progress       INTEGER NOT NULL DEFAULT 0,
                    target         INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(player_uuid, achievement_id)
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS snapshots (
                    label      TEXT PRIMARY KEY,
                    payload    TEXT NOT NULL,
                    phase      TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
        }
    }

    // ── Migration 3: indexes for leaderboard queries ──────────────────────────

    private void migration3(Connection c) throws SQLException {
        LOG.info("[KMC/Storage] Running migration 3: indexes");
        try (var st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_players_points ON players(points DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_teams_points ON teams(points DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_point_audit_player ON point_audit(player_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_point_audit_round ON point_audit(round)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_player_history_uuid ON player_history(player_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_created ON snapshots(created_at DESC)");
        }
    }

    // ── Migration 4: achievement performance indexes ──────────────────────────

    private void migration4(Connection c) throws SQLException {
        LOG.info("[KMC/Storage] Running migration 4: achievement indexes");
        try (var st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_achievements_player ON player_achievements(player_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_achievements_id ON player_achievements(achievement_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_achievements_unlocked ON player_achievements(unlocked_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_progress_player ON achievement_progress(player_uuid)");
        }
    }

    // ── Migration 5: additional query-path indexes ────────────────────────────

    private void migration5(Connection c) throws SQLException {
        LOG.info("[KMC/Storage] Running migration 5: query-path indexes");
        try (var st = c.createStatement()) {
            // HoFNpcManager queries player_history by event_number for MVP lookup
            st.execute("CREATE INDEX IF NOT EXISTS idx_player_history_event ON player_history(event_number)");
            // EventStatsBookBuilder queries point_audit by game_id
            st.execute("CREATE INDEX IF NOT EXISTS idx_point_audit_game_id ON point_audit(game_id)");
            // Time-range analysis on point_audit
            st.execute("CREATE INDEX IF NOT EXISTS idx_point_audit_timestamp ON point_audit(timestamp)");
        }
    }
}
