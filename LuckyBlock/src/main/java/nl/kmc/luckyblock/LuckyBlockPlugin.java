package nl.kmc.luckyblock;

import nl.kmc.luckyblock.commands.LuckyBlockCommand;
import nl.kmc.luckyblock.listeners.BlockBreakListener;
import nl.kmc.luckyblock.listeners.PlayerDeathListener;
import nl.kmc.luckyblock.managers.GameStateManager;
import nl.kmc.luckyblock.managers.LootTableManager;
import nl.kmc.luckyblock.managers.LuckyBlockTracker;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * KMC Lucky Block — standalone minigame plugin.
 *
 * <p>Uses KMCCore for arena (schematic paste/reset), teams, and points.
 * This plugin handles the loot table, lucky block detection, and game state.
 */
public final class LuckyBlockPlugin extends JavaPlugin {

    private static LuckyBlockPlugin instance;

    private KMCCore           kmcCore;
    private LootTableManager  lootTableManager;
    private LuckyBlockTracker tracker;
    private GameStateManager  gameStateManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling KMC Lucky Block.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        lootTableManager = new LootTableManager(this);
        tracker          = new LuckyBlockTracker(this);
        gameStateManager = new GameStateManager(this);

        getCommand("luckyblock").setExecutor(new LuckyBlockCommand(this));
        getCommand("luckyblock").setTabCompleter(new LuckyBlockCommand(this));

        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);

        getLogger().info("KMC Lucky Block enabled! Using KMCCore arena system.");
    }

    @Override
    public void onDisable() {
        if (gameStateManager != null) gameStateManager.forceStop();
    }

    public static LuckyBlockPlugin getInstance() { return instance; }
    public KMCCore           getKmcCore()      { return kmcCore; }
    public LootTableManager  getLootTable()    { return lootTableManager; }
    public LuckyBlockTracker getTracker()      { return tracker; }
    public GameStateManager  getGameState()    { return gameStateManager; }
}
