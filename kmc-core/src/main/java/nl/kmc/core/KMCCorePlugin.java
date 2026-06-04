package nl.kmc.core;

import nl.kmc.core.service.*;
import nl.kmc.storage.StorageModule;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class KMCCorePlugin extends JavaPlugin {

    private static KMCCorePlugin instance;

    private StorageModule        storage;
    private PlayerService        playerService;
    private TeamService          teamService;
    private TournamentService    tournamentService;
    private PointsService        pointsService;
    private GameRegistryService  gameRegistry;
    private ServiceContainer     container;

    @Override
    public void onLoad() { instance = this; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("points.yml", false);
        saveResource("messages.yml", false);

        // ── Storage ──────────────────────────────────────────────────────────
        storage = new StorageModule();
        try {
            storage.initialize(getDataFolder().toPath());
        } catch (SQLException e) {
            getLogger().severe("[KMC/Core] Failed to initialize storage: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Services (dependency order) ───────────────────────────────────────
        playerService     = new PlayerService(storage);
        playerService.warmUp();

        tournamentService = new TournamentService(this, storage, null); // teams injected below
        teamService       = new TeamService(this, storage, playerService);
        teamService.load();

        // Re-create with teams wired
        tournamentService = new TournamentService(this, storage, teamService);
        tournamentService.load();

        pointsService  = new PointsService(this, playerService, teamService, storage, tournamentService);
        pointsService.loadConfig();

        gameRegistry   = new GameRegistryService();

        // ── DI container ─────────────────────────────────────────────────────
        container = new ServiceContainer();
        container.register(PlayerService.class,       playerService);
        container.register(TeamService.class,         teamService);
        container.register(TournamentService.class,   tournamentService);
        container.register(PointsService.class,       pointsService);
        container.register(GameRegistryService.class, gameRegistry);
        container.register(StorageModule.class,       storage);

        // ── Public API ───────────────────────────────────────────────────────
        // KMCCore (V1) is the single source of truth and registers the KMCApi via
        // KMCApiProvider. We intentionally do NOT register one here — a second API
        // would mean a second data store (the old points "split-brain"). The V2
        // services above stay available via getContainer() for game bootstrap
        // (e.g. PlayerService for StatisticsService, GameRegistryService).

        // ── Listeners ────────────────────────────────────────────────────────
        // V2 session/chat/protection listeners are intentionally NOT registered:
        // KMCCore (V1) owns player join/quit, team chat, and lobby protection.
        // Registering them here would double-handle those events.

        getLogger().info("KMC Core V2 (data/service library) enabled — event #" + tournamentService.getEventNumber());
    }

    @Override
    public void onDisable() {
        if (playerService  != null) playerService.saveAll();
        if (teamService    != null) teamService.saveAll();
        if (tournamentService != null) tournamentService.save();
        if (storage        != null) storage.shutdown();
        // The KMCApi slot is owned and cleared by KMCCore (V1), not here.
        getLogger().info("KMC Core V2 disabled.");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public static KMCCorePlugin getInstance()              { return instance; }
    public StorageModule        getStorage()               { return storage; }
    public PlayerService        getPlayerService()         { return playerService; }
    public TeamService          getTeamService()           { return teamService; }
    public TournamentService    getTournamentService()     { return tournamentService; }
    public PointsService        getPointsService()         { return pointsService; }
    public GameRegistryService  getGameRegistry()          { return gameRegistry; }
    public ServiceContainer     getContainer()             { return container; }
}
