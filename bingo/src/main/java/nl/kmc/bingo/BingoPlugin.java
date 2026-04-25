package nl.kmc.bingo;

import nl.kmc.bingo.commands.BingoCommand;
import nl.kmc.bingo.listeners.InventoryListener;
import nl.kmc.bingo.managers.CardGenerator;
import nl.kmc.bingo.managers.GameManager;
import nl.kmc.bingo.managers.WorldManager;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bingo Teams — strategic objective minigame for KMC tournaments.
 *
 * <p>Each team gets a 5×5 bingo card. Items only — collect specific
 * materials. Same card for everyone. 15-minute timer. Most squares
 * wins; full card = instant win. Independent teams, no PvP.
 *
 * <p>World strategy: clones a pre-built "bingo_template" world per
 * game so every match starts pristine. Auto-disposed after.
 */
public final class BingoPlugin extends JavaPlugin {

    private static BingoPlugin instance;

    public static final String GAME_ID = "bingo_teams";

    private KMCCore        kmcCore;
    private CardGenerator  cardGenerator;
    private WorldManager   worldManager;
    private GameManager    gameManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling Bingo.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        cardGenerator = new CardGenerator(this);
        worldManager  = new WorldManager(this);
        gameManager   = new GameManager(this);

        BingoCommand cmd = new BingoCommand(this);
        var bukkitCmd = getCommand("bingo");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }

        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);

        // Auto-start hook
        kmcCore.getApi().onGameStart(gameId -> {
            if (!GAME_ID.equals(gameId)) return;
            getLogger().info("KMCCore picked Bingo — preparing world...");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String error = gameManager.startGame();
                if (error != null) {
                    getLogger().warning("Bingo auto-start failed: " + error);
                    if (kmcCore.getAutomationManager().isRunning()) {
                        kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }
            }, 40L);
        });

        getLogger().info("Bingo enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.forceStop();
        getLogger().info("Bingo disabled.");
    }

    public static BingoPlugin getInstance() { return instance; }

    public KMCCore       getKmcCore()       { return kmcCore; }
    public CardGenerator getCardGenerator() { return cardGenerator; }
    public WorldManager  getWorldManager()  { return worldManager; }
    public GameManager   getGameManager()   { return gameManager; }
}
