package nl.kmc.bridge;

import nl.kmc.bridge.commands.BridgeCommand;
import nl.kmc.bridge.listeners.BridgeListener;
import nl.kmc.bridge.managers.ArenaManager;
import nl.kmc.bridge.managers.BlockTracker;
import nl.kmc.bridge.managers.GameManager;
import nl.kmc.bridge.managers.KitManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bridge — Hypixel-style 2v2/4v4 bridge battle minigame.
 *
 * <p>Players spawn with a sword, bow, kit of team-colored wool, and
 * a pickaxe. Bridge across the void to the opponent's goal hole and
 * jump in to score. PvP active. First team to N goals wins; tiebreak
 * by total kills.
 */
public final class TheBridgePlugin extends JavaPlugin {

    private static TheBridgePlugin instance;

    public static final String GAME_ID = "the_bridge";

    private KMCCore       kmcCore;
    private ArenaManager  arenaManager;
    private KitManager    kitManager;
    private BlockTracker  blockTracker;
    private GameManager   gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling The Bridge.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager = new ArenaManager(this);
        kitManager   = new KitManager(this);
        blockTracker = new BlockTracker(this);
        gameManager  = new GameManager(this);

        var cmd = new BridgeCommand(this);
        var bukkitCmd = getCommand("bridge");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new BridgeListener(this), this);

        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked The Bridge — launching countdown.");
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

        getLogger().info("The Bridge enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        if (blockTracker != null) blockTracker.cancelTasks();
    }

    public static TheBridgePlugin getInstance() { return instance; }

    public KMCCore      getKmcCore()      { return kmcCore; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public KitManager   getKitManager()   { return kitManager; }
    public BlockTracker getBlockTracker() { return blockTracker; }
    public GameManager  getGameManager()  { return gameManager; }
}
