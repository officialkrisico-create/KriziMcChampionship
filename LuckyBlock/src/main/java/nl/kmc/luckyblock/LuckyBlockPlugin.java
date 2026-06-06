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
                    .description("Sla lucky blocks kapot voor willekeurige loot — laatste speler die overleeft wint!")
                    .objective("Blijf als laatste speler over.")
                    .build();

            gameRegistry.register(reg);
            gameManagerV2 = new LuckyBlockGameManagerV2(this, reg, statsService);

            // Register in the unified Setup Dashboard / Event Validation System.
            var setupService = coreV2.getContainer().get(nl.kmc.core.setup.SetupService.class);
            if (setupService != null) setupService.register(buildSetup());

            GameIntroCardRegistry.register(GameIntroCard.builder(GAME_ID, "Lucky Block")
                    .objective("Laatste speler die overleeft wint")
                    .addScoringLine("+ptn — Lucky block-loot")
                    .addScoringLine("+35 ptn — Eliminatie")
                    .addScoringLine("+5 ptn — Overlevingsbonus")
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

    /** Setup descriptor for the unified Setup Dashboard / EVS. */
    private nl.kmc.core.setup.GameSetup buildSetup() {
        return new nl.kmc.core.setup.GameSetup() {
            @Override public String   gameId()      { return GAME_ID; }
            @Override public String   displayName() { return "Lucky Block"; }
            @Override public Material  icon()        { return Material.YELLOW_CONCRETE; }
            @Override public boolean   isReady() {
                return kmcCore != null && kmcCore.getTeamManager().getAllTeams().size() >= 2;
            }
            @Override public java.util.List<String> issues() {
                java.util.List<String> out = new java.util.ArrayList<>();
                if (kmcCore == null || kmcCore.getTeamManager().getAllTeams().size() < 2)
                    out.add("Minder dan 2 teams (Lucky Block gebruikt KMCCore team-spawns)");
                return out;
            }
            @Override public java.util.List<nl.kmc.core.setup.SetupStep> steps(org.bukkit.entity.Player viewer) {
                java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();
                String mat = getConfig().getString("lucky-block.material", getConfig().getString("material", "SPONGE"));
                s.add(nl.kmc.core.setup.SetupStep.info("Lucky block materiaal", mat, true, Material.SPONGE));
                int teams = kmcCore != null ? kmcCore.getTeamManager().getAllTeams().size() : 0;
                s.add(nl.kmc.core.setup.SetupStep.info("Teams", teams + " (gebruikt KMCCore teams + lobby)",
                        teams >= 2, Material.WHITE_BANNER));
                s.add(nl.kmc.core.setup.SetupStep.info("Arena",
                        "Bouw de PvP-arena handmatig en plaats lucky blocks", true, Material.YELLOW_CONCRETE));
                return s;
            }
        };
    }
}
