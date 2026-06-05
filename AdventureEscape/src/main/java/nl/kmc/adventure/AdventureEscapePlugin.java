package nl.kmc.adventure;

import nl.kmc.core.KMCConstants;
import nl.kmc.core.KMCCorePlugin;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.PlayerService;
import nl.kmc.core.event.GameStartEvent;
import nl.kmc.game.api.GameIntroCard;
import nl.kmc.game.api.GameIntroCardRegistry;
import nl.kmc.adventure.commands.AdventureCommand;
import nl.kmc.adventure.listeners.BlockStepListener;
import nl.kmc.adventure.listeners.LineCrossListener;
import nl.kmc.adventure.listeners.OutOfBoundsListener;
import nl.kmc.adventure.listeners.PlayerJoinQuitListener;
import nl.kmc.adventure.managers.AdventureEscapeGameManagerV2;
import nl.kmc.adventure.managers.ArenaManager;
import nl.kmc.adventure.managers.CheckpointManager;
import nl.kmc.adventure.managers.EffectBlockManager;
import nl.kmc.adventure.managers.RaceManager;
import nl.kmc.adventure.managers.RaceScoreboard;
import nl.kmc.adventure.managers.TrialKeyManager;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdventureEscapePlugin extends JavaPlugin {

    private static AdventureEscapePlugin instance;
    public static final String GAME_ID = "adventure_escape";

    private KMCCore                    kmcCore;
    private ArenaManager               arenaManager;
    private EffectBlockManager         effectBlockManager;
    private CheckpointManager          checkpointManager;
    private RaceManager                raceManager;
    private RaceScoreboard             raceScoreboard;
    private TrialKeyManager            trialKeyManager;
    private OutOfBoundsListener        oobListener;
    private AdventureEscapeGameManagerV2 gameManagerV2;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!(getServer().getPluginManager().getPlugin("KMCCore") instanceof KMCCore core)) {
            getLogger().severe("KMCCore not found! Disabling Adventure Escape.");
            setEnabled(false);
            return;
        }
        kmcCore = core;

        arenaManager       = new ArenaManager(this);
        effectBlockManager = new EffectBlockManager(this);
        checkpointManager  = new CheckpointManager(this);
        raceManager        = new RaceManager(this);
        raceScoreboard     = new RaceScoreboard(this);
        trialKeyManager    = new TrialKeyManager(this);
        oobListener        = new OutOfBoundsListener(this);

        // ── V2 tournament integration ────────────────────────────────────────
        KMCCorePlugin coreV2 = (KMCCorePlugin) getServer().getPluginManager().getPlugin(KMCConstants.CORE_V2_PLUGIN_NAME);
        if (coreV2 != null) {
            PlayerService       playerService = coreV2.getContainer().get(PlayerService.class);
            GameRegistryService gameRegistry  = coreV2.getContainer().get(GameRegistryService.class);
            StatisticsService   statsService  = new StatisticsService(this, playerService);

            GameRegistration reg = GameRegistration.builder(GAME_ID, "Adventure Escape")
                    .icon(Material.MAP)
                    .minPlayers(2)
                    .description("Race through a multi-lap adventure course as fast as possible.")
                    .objective("Complete all laps first — or be furthest along when time runs out.")
                    .build();

            gameRegistry.register(reg);
            gameManagerV2 = new AdventureEscapeGameManagerV2(this, reg, statsService);

            // Register in the unified Setup Dashboard / Event Validation System.
            var setupService = coreV2.getContainer().get(nl.kmc.core.setup.SetupService.class);
            if (setupService != null) setupService.register(buildSetup());

            GameIntroCardRegistry.register(GameIntroCard.builder(GAME_ID, "Adventure Escape")
                    .objective("Complete all laps first to win")
                    .addScoringLine("+50 pts — Lap completed")
                    .addScoringLine("+150 pts — Race finished")
                    .addScoringLine("+500 pts — 1st Place")
                    .build());

            getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onGameStart(GameStartEvent event) {
                    if (!GAME_ID.equals(event.getGame().getId())) return;
                    Bukkit.getScheduler().runTaskLater(AdventureEscapePlugin.this, () -> {
                        if (!gameManagerV2.start())
                            getLogger().warning("[Adventure] V2 start() failed — arena not ready.");
                    }, 40L);
                }
            }, this);

            getLogger().info("[Adventure] V2 tournament integration enabled.");
        } else {
            kmcCore.getApi().onGameStart(gameId -> {
                if (!GAME_ID.equals(gameId)) return;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    String error = raceManager.startCountdown();
                    if (error != null) {
                        getLogger().warning("Auto-start failed: " + error);
                        if (kmcCore.getAutomationManager().isRunning())
                            kmcCore.getAutomationManager().onGameEnd(null);
                    }
                }, 40L);
            });
        }

        var cmd = new AdventureCommand(this);
        getCommand("adventure").setExecutor(cmd); getCommand("adventure").setTabCompleter(cmd);
        getCommand("ae").setExecutor(cmd);        getCommand("ae").setTabCompleter(cmd);

        getServer().getPluginManager().registerEvents(new BlockStepListener(this),     this);
        getServer().getPluginManager().registerEvents(new LineCrossListener(this),     this);
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(trialKeyManager,                 this);

        oobListener.start();
        getLogger().info("Adventure Escape enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManagerV2 != null && gameManagerV2.isRunning()) gameManagerV2.end();
        if (oobListener    != null) oobListener.stop();
        if (raceManager    != null) raceManager.forceStop();
        if (raceScoreboard != null) raceScoreboard.cleanup();
    }

    public static AdventureEscapePlugin getInstance()             { return instance; }
    public KMCCore                      getKmcCore()              { return kmcCore; }
    public ArenaManager                 getArenaManager()         { return arenaManager; }
    public EffectBlockManager           getEffectBlockManager()   { return effectBlockManager; }
    public CheckpointManager            getCheckpointManager()    { return checkpointManager; }
    public RaceManager                  getRaceManager()          { return raceManager; }
    public RaceScoreboard               getRaceScoreboard()       { return raceScoreboard; }
    public TrialKeyManager              getTrialKeyManager()      { return trialKeyManager; }
    public AdventureEscapeGameManagerV2 getGameManagerV2()        { return gameManagerV2; }

    /** Setup descriptor for the unified Setup Dashboard / EVS. */
    private nl.kmc.core.setup.GameSetup buildSetup() {
        return new nl.kmc.core.setup.GameSetup() {
            @Override public String   gameId()      { return GAME_ID; }
            @Override public String   displayName() { return "Adventure Escape"; }
            @Override public Material  icon()        { return Material.MAP; }
            @Override public boolean   isReady()     { return arenaManager != null && arenaManager.isReady(); }
            @Override public java.util.List<String> issues() {
                java.util.List<String> out = new java.util.ArrayList<>();
                if (arenaManager == null) return out;
                if (arenaManager.getRaceWorld() == null) out.add("Geen race-wereld ingesteld");
                if (arenaManager.getSpawns().isEmpty()) out.add("Geen spawns ingesteld");
                if (arenaManager.getLaps() <= 0) out.add("Aantal laps niet ingesteld");
                return out;
            }
            @Override public java.util.List<nl.kmc.core.setup.SetupStep> steps(org.bukkit.entity.Player viewer) {
                java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();
                var am = arenaManager;
                if (am == null) return s;
                boolean w = am.getRaceWorld() != null;
                s.add(nl.kmc.core.setup.SetupStep.action("Race wereld",
                        w ? "✓ " + am.getRaceWorld().getName() : "niet ingesteld", w, Material.GRASS_BLOCK,
                        p -> { am.setRaceWorld(p.getWorld()); p.sendMessage("§a[Setup] Race-wereld gezet op " + p.getWorld().getName()); },
                        "Klik: zet de race-wereld op die van jou"));
                int spawns = am.getSpawns().size();
                s.add(nl.kmc.core.setup.SetupStep.action("Spawns", spawns + " (min. 1)", spawns >= 1, Material.RED_BED,
                        p -> { am.addSpawn(p.getLocation()); p.sendMessage("§a[Setup] Spawn #" + am.getSpawns().size() + " toegevoegd."); },
                        "Klik: voeg een spawn toe op jouw locatie"));
                int laps = am.getLaps();
                s.add(nl.kmc.core.setup.SetupStep.action("Laps", laps + " ronden", laps > 0, Material.CLOCK,
                        p -> { am.setLaps(laps >= 5 ? 1 : laps + 1); p.sendMessage("§a[Setup] Laps gezet."); },
                        "Klik: cycle aantal laps 1 → 5"));
                s.add(nl.kmc.core.setup.SetupStep.info("Start/finish + checkpoints",
                        "via /ae setstartline, setfinishline, setcheckpoint", true, Material.OAK_SIGN));
                return s;
            }
        };
    }
}
