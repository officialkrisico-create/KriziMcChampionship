package nl.kmc.kmccore.module;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.discord.DiscordWebhook;
import nl.kmc.kmccore.health.HealthMonitor;
import nl.kmc.kmccore.leaderboard.BossBarLeaderboardManager;
import nl.kmc.kmccore.lobby.LobbyNPCManager;
import nl.kmc.kmccore.maps.MapRotationManager;
import nl.kmc.kmccore.preferences.PlayerPreferencesManager;
import nl.kmc.kmccore.readyup.ReadyUpManager;
import nl.kmc.kmccore.spectator.SpectatorManager;

/**
 * Megapatch engagement and operational managers.
 *
 * <p>DiscordWebhook is created first because HealthMonitor logs to it.
 * Depends on {@link CoreModule} and {@link InfraModule} being enabled first.
 */
public class MegapatchModule implements PluginModule {

    private final KMCCore plugin;

    private DiscordWebhook            discordHook;
    private PlayerPreferencesManager  playerPreferences;
    private MapRotationManager        mapRotation;
    private SpectatorManager          spectatorManager;
    private LobbyNPCManager           lobbyNPCManager;
    private BossBarLeaderboardManager bossBarLeaderboard;
    private ReadyUpManager            readyUpManager;
    private HealthMonitor             healthMonitor;

    public MegapatchModule(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public void enable() {
        // Order matters: discordHook before HealthMonitor
        discordHook        = new DiscordWebhook(plugin);
        playerPreferences  = new PlayerPreferencesManager(plugin);
        mapRotation        = new MapRotationManager(plugin);
        spectatorManager   = new SpectatorManager(plugin);
        lobbyNPCManager    = new LobbyNPCManager(plugin);
        bossBarLeaderboard = new BossBarLeaderboardManager(plugin);
        readyUpManager     = new ReadyUpManager(plugin);
        healthMonitor      = new HealthMonitor(plugin);
    }

    /** Starts background tasks that require all managers to be fully wired. */
    public void startBackgroundTasks() {
        if (plugin.getConfig().getBoolean("leaderboard-bar.enabled", true))
            bossBarLeaderboard.start();
        healthMonitor.start();
    }

    @Override
    public void disable() {
        if (bossBarLeaderboard != null) bossBarLeaderboard.stop();
        if (healthMonitor      != null) healthMonitor.stop();
        if (lobbyNPCManager    != null) lobbyNPCManager.despawnAll();
        if (readyUpManager     != null) readyUpManager.shutdown();
        if (playerPreferences  != null) playerPreferences.shutdown();
        if (spectatorManager   != null) spectatorManager.shutdown();
        // mapRotation: saves on every change — nothing to flush on shutdown
    }

    public DiscordWebhook            getDiscordHook()        { return discordHook; }
    public PlayerPreferencesManager  getPlayerPreferences()  { return playerPreferences; }
    public MapRotationManager        getMapRotation()        { return mapRotation; }
    public SpectatorManager          getSpectatorManager()   { return spectatorManager; }
    public LobbyNPCManager           getLobbyNPCManager()    { return lobbyNPCManager; }
    public BossBarLeaderboardManager getBossBarLeaderboard() { return bossBarLeaderboard; }
    public ReadyUpManager            getReadyUpManager()     { return readyUpManager; }
    public HealthMonitor             getHealthMonitor()      { return healthMonitor; }
}
