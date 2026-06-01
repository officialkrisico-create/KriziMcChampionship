package nl.kmc.storage;

import nl.kmc.storage.cache.CachedPlayerRepository;
import nl.kmc.storage.cache.CachedTeamRepository;
import nl.kmc.storage.repository.*;
import nl.kmc.storage.sqlite.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Entry point for the storage layer. Create one instance per plugin lifecycle.
 * Provides access to all repositories after {@link #initialize(Path)} is called.
 */
public final class StorageModule {

    private static final Logger LOG = Logger.getLogger(StorageModule.class.getName());

    private final Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kmc-storage");
        t.setDaemon(true);
        return t;
    });

    private SQLiteDataSource dataSource;

    private CachedPlayerRepository playerRepository;
    private CachedTeamRepository   teamRepository;
    private TournamentRepository   tournamentRepository;
    private StatisticsRepository   statisticsRepository;
    private HistoryRepository      historyRepository;
    private SnapshotRepository     snapshotRepository;
    private AchievementRepository  achievementRepository;

    public void initialize(Path dataFolder) throws SQLException {
        Path dbFile = dataFolder.resolve("kmc_v2.db");
        dataSource = new SQLiteDataSource(dbFile);
        dataSource.connect();

        new SQLiteMigrationRunner(dataSource).run();

        playerRepository      = new CachedPlayerRepository(new SQLitePlayerRepository(dataSource, executor));
        teamRepository        = new CachedTeamRepository(new SQLiteTeamRepository(dataSource, executor));
        tournamentRepository  = new SQLiteTournamentRepository(dataSource, executor);
        statisticsRepository  = new SQLiteStatisticsRepository(dataSource, executor);
        historyRepository     = new SQLiteHistoryRepository(dataSource, executor);
        snapshotRepository    = new SQLiteSnapshotRepository(dataSource, executor);
        achievementRepository = new SQLiteAchievementRepository(dataSource, executor);

        // Warm caches synchronously at startup — small data, safe to block once
        playerRepository.warmUp().join();
        teamRepository.warmUp().join();

        LOG.info("[KMC/Storage] StorageModule initialized.");
    }

    public void shutdown() {
        if (dataSource != null) dataSource.disconnect();
    }

    public CachedPlayerRepository players()      { return playerRepository; }
    public CachedTeamRepository   teams()        { return teamRepository; }
    public TournamentRepository   tournament()   { return tournamentRepository; }
    public StatisticsRepository   statistics()   { return statisticsRepository; }
    public HistoryRepository      history()      { return historyRepository; }
    public SnapshotRepository     snapshots()    { return snapshotRepository; }
    public AchievementRepository  achievements() { return achievementRepository; }
    public Executor               executor()     { return executor; }
}
