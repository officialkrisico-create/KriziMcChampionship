package nl.kmc.kmccore.module;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.database.DatabaseManager;
import nl.kmc.kmccore.managers.PlayerDataManager;
import nl.kmc.kmccore.managers.PointsManager;
import nl.kmc.kmccore.managers.TeamManager;

/**
 * Persistence and core domain layer.
 *
 * <p>Must be the first module enabled; all other modules depend on at least
 * one manager here.
 */
public class CoreModule implements PluginModule {

    private final KMCCore plugin;

    private DatabaseManager   databaseManager;
    private TeamManager       teamManager;
    private PlayerDataManager playerDataManager;
    private PointsManager     pointsManager;
    private KMCApi            api;

    public CoreModule(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public void enable() {
        databaseManager   = new DatabaseManager(plugin);
        databaseManager.connect();
        teamManager       = new TeamManager(plugin);
        playerDataManager = new PlayerDataManager(plugin);
        pointsManager     = new PointsManager(plugin);
        api               = new KMCApi(plugin);
    }

    @Override
    public void disable() {
        if (playerDataManager != null) playerDataManager.saveAll();
        if (teamManager       != null) teamManager.saveAll();
        if (databaseManager   != null) databaseManager.disconnect();
    }

    public DatabaseManager   getDatabaseManager()   { return databaseManager; }
    public TeamManager       getTeamManager()       { return teamManager; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public PointsManager     getPointsManager()     { return pointsManager; }
    public KMCApi            getApi()               { return api; }
}
