package nl.kmc.storage.sqlite;

import nl.kmc.storage.model.StoredTournamentState;
import nl.kmc.storage.repository.TournamentRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SQLiteTournamentRepository extends AsyncBase implements TournamentRepository {

    public SQLiteTournamentRepository(SQLiteDataSource dataSource, Executor executor) {
        super(dataSource, executor);
    }

    @Override
    public CompletableFuture<Optional<StoredTournamentState>> load() {
        return async(() -> {
            try {
                Connection c = dataSource.get();
                StoredTournamentState s = new StoredTournamentState();
                s.active        = Boolean.parseBoolean(get(c, "active", "false"));
                s.currentRound  = Integer.parseInt(get(c, "current_round", "1"));
                s.eventNumber   = Integer.parseInt(get(c, "event_number", "1"));
                s.activeGameId  = get(c, "active_game", null);
                s.tournamentPhase = get(c, "tournament_phase", "WAITING");
                String played   = get(c, "played_games", "");
                if (!played.isBlank()) {
                    s.playedGameIds.addAll(Arrays.asList(played.split(",")));
                }
                return Optional.of(s);
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    @Override
    public CompletableFuture<Void> save(StoredTournamentState s) {
        return asyncVoid(() -> {
            Connection c = dataSource.get();
            set(c, "active",           String.valueOf(s.active));
            set(c, "current_round",    String.valueOf(s.currentRound));
            set(c, "event_number",     String.valueOf(s.eventNumber));
            set(c, "active_game",      s.activeGameId != null ? s.activeGameId : "");
            set(c, "tournament_phase", s.tournamentPhase != null ? s.tournamentPhase : "WAITING");
            set(c, "played_games",     String.join(",", s.playedGameIds));
        });
    }

    @Override
    public CompletableFuture<Void> clear() {
        return asyncVoid(() -> {
            try (var st = dataSource.get().createStatement()) {
                st.execute("DELETE FROM tournament_state");
            }
        });
    }

    private String get(Connection c, String key, String defaultValue) throws SQLException {
        try (var ps = c.prepareStatement("SELECT value FROM tournament_state WHERE key=?")) {
            ps.setString(1, key);
            var rs = ps.executeQuery();
            if (rs.next()) {
                String v = rs.getString("value");
                return (v != null && !v.isBlank()) ? v : defaultValue;
            }
            return defaultValue;
        }
    }

    private void set(Connection c, String key, String value) throws SQLException {
        try (var ps = c.prepareStatement("""
            INSERT INTO tournament_state (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """)) {
            ps.setString(1, key);
            ps.setString(2, value != null ? value : "");
            ps.executeUpdate();
        }
    }
}
