package nl.kmc.sg;

import nl.kmc.sg.commands.SGCommand;
import nl.kmc.sg.listeners.SGListener;
import nl.kmc.sg.managers.ArenaManager;
import nl.kmc.sg.managers.ChestStocker;
import nl.kmc.sg.managers.GameManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Survival Games — Hunger Games-style PvP minigame.
 *
 * <p>Players spawn around a cornucopia in a pre-built arena. Plugin
 * stocks all chests in the world (within border radius) with random
 * loot. After 10s bloodbath, full PvP for 8 minutes. Last 2 minutes
 * trigger deathmatch — world border shrinks toward cornucopia.
 *
 * <p>1 life per player, last alive wins.
 */
public final class SurvivalGamesPlugin extends JavaPlugin {

    private static SurvivalGamesPlugin instance;
    public static final String GAME_ID = "survival_games";

    private KMCCore       kmcCore;
    private ArenaManager  arenaManager;
    private ChestStocker  chestStocker;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling Survival Games.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager = new ArenaManager(this);
        chestStocker = new ChestStocker(this);
        gameManager  = new GameManager(this);

        var cmd = new SGCommand(this);
        var bukkitCmd = getCommand("survivalgames");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new SGListener(this), this);

        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Survival Games — preparing match.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("SG auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("Survival Games enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        if (chestStocker != null) chestStocker.cancelTasks();
    }

    public static SurvivalGamesPlugin getInstance() { return instance; }

    public KMCCore      getKmcCore()      { return kmcCore; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public ChestStocker getChestStocker() { return chestStocker; }
    public GameManager  getGameManager()  { return gameManager; }
}
