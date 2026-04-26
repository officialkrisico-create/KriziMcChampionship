package nl.kmc.tgttos;

import nl.kmc.tgttos.commands.TGTTOSCommand;
import nl.kmc.tgttos.listeners.MovementListener;
import nl.kmc.tgttos.managers.GameManager;
import nl.kmc.tgttos.managers.MapManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TGTTOS — To Get To The Other Side. Multi-round race minigame.
 *
 * <p>Each game = 3 rounds (configurable) on different maps. Each
 * round, players race from start to a finish region. Death =
 * respawn at start of same round. Round ends when all finish
 * or 90s timer expires. Cumulative point ranking across all rounds.
 */
public final class TGTTOSPlugin extends JavaPlugin {

    private static TGTTOSPlugin instance;

    public static final String GAME_ID = "tgttos";

    private KMCCore     kmcCore;
    private MapManager  mapManager;
    private GameManager gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling TGTTOS.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        mapManager  = new MapManager(this);
        gameManager = new GameManager(this);

        var cmd = new TGTTOSCommand(this);
        var bukkitCmd = getCommand("tgttos");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new MovementListener(this), this);

        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked TGTTOS — preparing rotation.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("Auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("TGTTOS enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
    }

    public static TGTTOSPlugin getInstance() { return instance; }

    public KMCCore     getKmcCore()     { return kmcCore; }
    public MapManager  getMapManager()  { return mapManager; }
    public GameManager getGameManager() { return gameManager; }
}
