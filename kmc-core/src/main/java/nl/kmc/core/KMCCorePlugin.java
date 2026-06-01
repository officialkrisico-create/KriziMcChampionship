package nl.kmc.core;

import nl.kmc.core.api.KMCApiImpl;
import nl.kmc.core.api.KMCApiProvider;
import nl.kmc.core.listener.CoreProtectionListener;
import nl.kmc.core.listener.PlayerSessionListener;
import nl.kmc.core.listener.TeamChatListener;
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
        KMCApiProvider.set(new KMCApiImpl(teamService, pointsService, tournamentService, gameRegistry));

        // ── Listeners ────────────────────────────────────────────────────────
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerSessionListener(this, playerService, teamService), this);
        pm.registerEvents(new CoreProtectionListener(this, tournamentService), this);
        pm.registerEvents(new TeamChatListener(this, teamService), this);

        getLogger().info("KMC Core V2 enabled — event #" + tournamentService.getEventNumber());
    }

    @Override
    public void onDisable() {
        if (playerService  != null) playerService.saveAll();
        if (teamService    != null) teamService.saveAll();
        if (tournamentService != null) tournamentService.save();
        if (storage        != null) storage.shutdown();
        KMCApiProvider.clear();
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
