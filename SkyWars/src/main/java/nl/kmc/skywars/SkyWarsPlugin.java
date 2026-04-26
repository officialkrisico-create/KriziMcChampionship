package nl.kmc.skywars;

import nl.kmc.skywars.commands.SkyWarsCommand;
import nl.kmc.skywars.listeners.SkyWarsListener;
import nl.kmc.skywars.managers.ArenaManager;
import nl.kmc.skywars.managers.ChestStocker;
import nl.kmc.skywars.managers.GameManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Team SkyWars — PvP minigame on floating islands.
 *
 * <p>Each KMC team gets an island with chests (basic loot). A central
 * middle island has better loot. After a 10s grace period, PvP is
 * enabled. Last team standing wins.
 */
public final class SkyWarsPlugin extends JavaPlugin {

    private static SkyWarsPlugin instance;
    public static final String GAME_ID = "team_skywars";

    private KMCCore       kmcCore;
    private ArenaManager  arenaManager;
    private ChestStocker  chestStocker;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling SkyWars.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager = new ArenaManager(this);
        chestStocker = new ChestStocker(this);
        gameManager  = new GameManager(this);

        var cmd = new SkyWarsCommand(this);
        var bukkitCmd = getCommand("skywars");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new SkyWarsListener(this), this);

        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked SkyWars — preparing match.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("SkyWars auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("SkyWars enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
    }

    public static SkyWarsPlugin getInstance() { return instance; }

    public KMCCore      getKmcCore()      { return kmcCore; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public ChestStocker getChestStocker() { return chestStocker; }
    public GameManager  getGameManager()  { return gameManager; }
}
