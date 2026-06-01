package nl.kmc.luckyblock;

import nl.kmc.core.KMCConstants;
import nl.kmc.core.KMCCorePlugin;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.PlayerService;
import nl.kmc.core.event.GameStartEvent;
import nl.kmc.game.api.GameIntroCard;
import nl.kmc.game.api.GameIntroCardRegistry;
import nl.kmc.luckyblock.commands.LuckyBlockCommand;
import nl.kmc.luckyblock.listeners.BlockBreakListener;
import nl.kmc.luckyblock.listeners.PlayerDeathListener;
import nl.kmc.luckyblock.managers.GameStateManager;
import nl.kmc.luckyblock.managers.LootTableManager;
import nl.kmc.luckyblock.managers.LuckyBlockGameManagerV2;
import nl.kmc.luckyblock.managers.LuckyBlockTracker;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class LuckyBlockPlugin extends JavaPlugin {

    private static LuckyBlockPlugin instance;
    public static final String GAME_ID = "lucky_block";

    private KMCCore                kmcCore;
    private LootTableManager       lootTableManager;
    private LuckyBlockTracker      tracker;
    private GameStateManager       gameStateManager;
    private LuckyBlockGameManagerV2 gameManagerV2;

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

        // ── V2 tournament integration ────────────────────────────────────────
        KMCCorePlugin coreV2 = (KMCCorePlugin) getServer().getPluginManager().getPlugin(KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 != null) {
            PlayerService       playerService = coreV2.getContainer().get(PlayerService.class);
            GameRegistryService gameRegistry  = coreV2.getContainer().get(GameRegistryService.class);
            StatisticsService   statsService  = new StatisticsService(this, playerService);

            GameRegistration reg = GameRegistration.builder(GAME_ID, "Lucky Block")
                    .icon(Material.YELLOW_CONCRETE)
                    .minPlayers(2)
                    .description("Break lucky blocks to get random loot — last player alive wins!")
                    .objective("Be the last player standing.")
                    .build();

            gameRegistry.register(reg);
            gameManagerV2 = new LuckyBlockGameManagerV2(this, reg, statsService);

            GameIntroCardRegistry.register(GameIntroCard.builder(GAME_ID, "Lucky Block")
                    .objective("Last player alive wins")
                    .addScoringLine("+pts — Lucky block loot")
                    .addScoringLine("+35 pts — Elimination")
                    .addScoringLine("+5 pts — Survival bonus")
                    .build());

            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onGameStart(GameStartEvent event) {
                    if (!GAME_ID.equals(event.getGame().getId())) return;
                    Bukkit.getScheduler().runTaskLater(LuckyBlockPlugin.this, () -> {
                        if (!gameManagerV2.start())
                            getLogger().warning("[LuckyBlock] V2 start() failed — arena not configured.");
                    }, 40L);
                }
            }, this);

            getLogger().info("[LuckyBlock] V2 tournament integration enabled.");
        } else {
            // V1 fallback: no auto-start hook in original plugin — only manual /luckyblock start
        }

        getCommand("luckyblock").setExecutor(new LuckyBlockCommand(this));
        getCommand("luckyblock").setTabCompleter(new LuckyBlockCommand(this));
        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
        getLogger().info("KMC Lucky Block enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManagerV2 != null && gameManagerV2.isRunning()) gameManagerV2.end();
        else if (gameStateManager != null) gameStateManager.forceStop();
    }

    public static LuckyBlockPlugin getInstance()          { return instance; }
    public KMCCore                 getKmcCore()           { return kmcCore; }
    public LootTableManager        getLootTable()         { return lootTableManager; }
    public LuckyBlockTracker       getTracker()           { return tracker; }
    public GameStateManager        getGameState()         { return gameStateManager; }
    public LuckyBlockGameManagerV2 getGameManagerV2()     { return gameManagerV2; }
}
