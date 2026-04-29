package nl.kmc.kmccore;

import nl.kmc.kmccore.achievements.AchievementManager;
import nl.kmc.kmccore.announce.WelcomeBroadcaster;
import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.commands.*;
import nl.kmc.kmccore.database.DatabaseManager;
import nl.kmc.kmccore.discord.DiscordWebhook;
import nl.kmc.kmccore.gui.StatsGUI;
import nl.kmc.kmccore.health.HealthMonitor;
import nl.kmc.kmccore.history.TournamentHistoryManager;
import nl.kmc.kmccore.hof.HoFNpcManager;
import nl.kmc.kmccore.leaderboard.BossBarLeaderboardManager;
import nl.kmc.kmccore.listeners.*;
import nl.kmc.kmccore.lobby.LobbyArmorListener;
import nl.kmc.kmccore.lobby.LobbyNPCManager;
import nl.kmc.kmccore.managers.*;
import nl.kmc.kmccore.maps.MapRotationManager;
import nl.kmc.kmccore.npc.NPCManager;
import nl.kmc.kmccore.preferences.PlayerPreferencesManager;
import nl.kmc.kmccore.readyup.ReadyUpManager;
import nl.kmc.kmccore.scoreboard.ScoreboardManager;
import nl.kmc.kmccore.simulation.SimulationEngine;
import nl.kmc.kmccore.snapshot.SnapshotManager;
import nl.kmc.kmccore.spectator.SpectatorManager;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class KMCCore extends JavaPlugin {

    private static KMCCore instance;

    // --- existing managers (patch 8 baseline) ---
    private DatabaseManager    databaseManager;
    private TeamManager        teamManager;
    private PlayerDataManager  playerDataManager;
    private TournamentManager  tournamentManager;
    private GameManager        gameManager;
    private PointsManager      pointsManager;
    private ScoreboardManager  scoreboardManager;
    private NPCManager         npcManager;
    private AutomationManager  automationManager;
    private TabListManager     tabListManager;
    private ArenaManager       arenaManager;
    private SchematicManager   schematicManager;
    private HallOfFameManager  hallOfFameManager;
    private VoteGuiListener    voteGuiListener;

    private KMCApi api;

    // --- megapatch managers ---
    private SpectatorManager           spectatorManager;
    private LobbyNPCManager            lobbyNPCManager;
    private BossBarLeaderboardManager  bossBarLeaderboard;
    private ReadyUpManager             readyUpManager;
    private PlayerPreferencesManager   playerPreferences;
    private HealthMonitor              healthMonitor;
    private DiscordWebhook             discordHook;
    private MapRotationManager         mapRotation;

    // --- Phase 2: engagement layer ---
    private AchievementManager        achievementManager;
    private TournamentHistoryManager  tournamentHistoryManager;
    private HoFNpcManager             hoFNpcManager;
    private StatsGUI                  statsGUI;

    // --- Phase 2.5: simulation + snapshots ---
    private SnapshotManager   snapshotManager;
    private SimulationEngine  simulationEngine;

    @Override public void onLoad() { instance = this; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("points.yml",   false);

        MessageUtil.init(this);

        databaseManager   = new DatabaseManager(this);
        databaseManager.connect();
        teamManager       = new TeamManager(this);
        playerDataManager = new PlayerDataManager(this);
        pointsManager     = new PointsManager(this);
        api               = new KMCApi(this);
        tournamentManager = new TournamentManager(this);
        gameManager       = new GameManager(this);
        schematicManager  = new SchematicManager(this);
        arenaManager      = new ArenaManager(this);
        tabListManager    = new TabListManager(this);
        scoreboardManager = new ScoreboardManager(this);
        npcManager        = new NPCManager(this);
        hallOfFameManager = new HallOfFameManager(this);
        automationManager = new AutomationManager(this);

        voteGuiListener = new VoteGuiListener(this);

        // ---- Megapatch managers ----
        // Order matters: discordHook first because HealthMonitor uses it.
        discordHook         = new DiscordWebhook(this);
        playerPreferences   = new PlayerPreferencesManager(this);
        mapRotation         = new MapRotationManager(this);
        spectatorManager    = new SpectatorManager(this);
        lobbyNPCManager     = new LobbyNPCManager(this);
        bossBarLeaderboard  = new BossBarLeaderboardManager(this);
        readyUpManager      = new ReadyUpManager(this);
        healthMonitor       = new HealthMonitor(this);

        // ---- Phase 2: engagement layer ----
        achievementManager       = new AchievementManager(this);
        tournamentHistoryManager = new TournamentHistoryManager(this);
        statsGUI                 = new StatsGUI(this);
        hoFNpcManager            = new HoFNpcManager(this);

        // ---- Phase 2.5: simulation + snapshots ----
        snapshotManager   = new SnapshotManager(this);
        simulationEngine  = new SimulationEngine(this);

        registerCommands();
        registerListeners();

        // Start background services that depend on api/managers being ready
        if (getConfig().getBoolean("leaderboard-bar.enabled", true)) {
            bossBarLeaderboard.start();
        }
        healthMonitor.start();
        hoFNpcManager.start();

        // Welcome message — fires once when /kmctournament start runs
        WelcomeBroadcaster welcome = new WelcomeBroadcaster(this);
        api.onTournamentStart(welcome::broadcast);

        getLogger().info("KMCCore v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        // ---- Megapatch shutdown (BEFORE existing shutdown so saves succeed) ----
        if (bossBarLeaderboard != null) bossBarLeaderboard.stop();
        if (healthMonitor      != null) healthMonitor.stop();
        if (lobbyNPCManager    != null) lobbyNPCManager.despawnAll();
        if (readyUpManager     != null) readyUpManager.shutdown();
        if (playerPreferences  != null) playerPreferences.shutdown();
        if (spectatorManager   != null) spectatorManager.shutdown();
        if (hoFNpcManager      != null) hoFNpcManager.stop();
        // mapRotation: nothing to clean — saves on every change

        // ---- Existing shutdown ----
        if (playerDataManager != null) playerDataManager.saveAll();
        if (teamManager       != null) teamManager.saveAll();
        if (tournamentManager != null) tournamentManager.save();
        if (gameManager       != null) gameManager.save();
        if (automationManager != null) automationManager.stop();
        if (scoreboardManager != null) scoreboardManager.cleanup();
        if (npcManager        != null) npcManager.save();
        if (databaseManager   != null) databaseManager.disconnect();
        getLogger().info("KMCCore disabled.");
    }

    private void registerCommands() {
        setCmd("kmcteam",        new TeamCommand(this));
        setCmd("kmcstats",       new StatsCommand(this));
        setCmd("kmctournament",  new TournamentCommand(this));
        setCmd("kmcgame",        new GameCommand(this));
        setCmd("kmclb",          new LeaderboardCommand(this));
        setCmd("kmcnpc",         new NPCCommand(this));
        setCmd("kmcround",       new RoundCommand(this));
        setCmd("kmcpoints",      new PointsCommand(this));
        setCmd("tc",             new TeamChatCommand(this));
        setCmd("kmcvote",        new VoteCommand(this));
        setCmd("kmcauto",        new AutomationCommand(this));
        setCmd("kmcarena",       new ArenaCommand(this));
        setCmd("kmclobby",       new LobbyCommand(this));
        setCmd("kmcrandomteams", new RandomTeamsCommand(this));
        setCmd("kmchof",         new HofCommand(this));

        // ---- Megapatch commands (entries should be in plugin.yml) ----
        // setCmd already warns when a command is missing — KMCCore boots either way.
        setCmd("kmcprefs",    new AdminCommands.PreferencesCommand(this));
        setCmd("kmchealth",   new AdminCommands.HealthCommand(this));
        setCmd("kmcready",    new AdminCommands.ReadyCommand(this));
        setCmd("kmcmap",      new AdminCommands.MapCommand(this));
        setCmd("kmclobbynpc", new AdminCommands.LobbyNPCCommand(this));

        // ---- Phase 2.5: simulation + snapshot ----
        setCmd("event",       new EventCommand(this));
    }

    @SuppressWarnings("unchecked")
    private void setCmd(String name, Object executor) {
        var cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' not found in plugin.yml!");
            return;
        }
        cmd.setExecutor((org.bukkit.command.CommandExecutor) executor);
        if (executor instanceof org.bukkit.command.TabCompleter tc)
            cmd.setTabCompleter(tc);
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinQuitListener(this),  this);
        pm.registerEvents(new ChatListener(this),            this);
        pm.registerEvents(new PlayerKillListener(this),      this);
        pm.registerEvents(new VoteListener(this),            this);
        pm.registerEvents(voteGuiListener,                    this);
        pm.registerEvents(new LobbyProtectionListener(this), this);
        pm.registerEvents(new DeathListener(this),           this);
        pm.registerEvents(new GlobalPvPListener(this),       this);

        // ---- Phase 1: lobby armor ----
        LobbyArmorListener lobbyArmor = new LobbyArmorListener(this);
        pm.registerEvents(lobbyArmor, this);
        lobbyArmor.applyToAllOnline();

        // ---- Phase 2: stats GUI + HoF NPC interactions ----
        pm.registerEvents(statsGUI,      this);
        pm.registerEvents(hoFNpcManager, this);
    }

    // ---- Existing getters ----
    public static KMCCore getInstance() { return instance; }
    public DatabaseManager    getDatabaseManager()   { return databaseManager; }
    public TeamManager        getTeamManager()       { return teamManager; }
    public PlayerDataManager  getPlayerDataManager() { return playerDataManager; }
    public TournamentManager  getTournamentManager() { return tournamentManager; }
    public GameManager        getGameManager()       { return gameManager; }
    public PointsManager      getPointsManager()     { return pointsManager; }
    public ScoreboardManager  getScoreboardManager() { return scoreboardManager; }
    public NPCManager         getNpcManager()        { return npcManager; }
    public AutomationManager  getAutomationManager() { return automationManager; }
    public TabListManager     getTabListManager()    { return tabListManager; }
    public ArenaManager       getArenaManager()      { return arenaManager; }
    public SchematicManager   getSchematicManager()  { return schematicManager; }
    public HallOfFameManager  getHallOfFameManager() { return hallOfFameManager; }
    public VoteGuiListener    getVoteGuiListener()   { return voteGuiListener; }
    public KMCApi             getApi()               { return api; }

    // ---- Megapatch getters ----
    public SpectatorManager          getSpectatorManager()       { return spectatorManager; }
    public LobbyNPCManager           getLobbyNPCManager()        { return lobbyNPCManager; }
    public BossBarLeaderboardManager getBossBarLeaderboard()     { return bossBarLeaderboard; }
    public ReadyUpManager            getReadyUpManager()         { return readyUpManager; }
    public PlayerPreferencesManager  getPlayerPreferences()      { return playerPreferences; }
    public HealthMonitor             getHealthMonitor()          { return healthMonitor; }
    public DiscordWebhook            getDiscordHook()            { return discordHook; }
    public MapRotationManager        getMapRotation()            { return mapRotation; }

    // ---- Phase 2 getters ----
    public AchievementManager        getAchievementManager()       { return achievementManager; }
    public TournamentHistoryManager  getTournamentHistoryManager() { return tournamentHistoryManager; }
    public HoFNpcManager             getHoFNpcManager()            { return hoFNpcManager; }
    public StatsGUI                  getStatsGUI()                 { return statsGUI; }

    // ---- Phase 2.5 getters ----
    public SnapshotManager   getSnapshotManager()  { return snapshotManager; }
    public SimulationEngine  getSimulationEngine() { return simulationEngine; }
}