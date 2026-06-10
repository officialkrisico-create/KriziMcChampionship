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
import nl.kmc.kmccore.managers.CeremonyManager;
import nl.kmc.kmccore.presentation.CinematicManager;
import nl.kmc.kmccore.maps.MapRotationManager;
import nl.kmc.kmccore.module.*;
import nl.kmc.kmccore.npc.NPCManager;
import nl.kmc.kmccore.preferences.PlayerPreferencesManager;
import nl.kmc.kmccore.readyup.ReadyUpManager;
import nl.kmc.kmccore.scoreboard.ScoreboardManager;
import nl.kmc.kmccore.simulation.SimulationEngine;
import nl.kmc.kmccore.snapshot.SnapshotManager;
import nl.kmc.kmccore.spectator.SpectatorManager;
import nl.kmc.kmccore.util.MessageUtil;
import nl.kmc.stats.service.AchievementService;
import org.bukkit.plugin.java.JavaPlugin;

public final class KMCCore extends JavaPlugin {

    private static KMCCore instance;

    // ── Modules (dependency order) ────────────────────────────────────────
    private CoreModule       coreModule;
    private InfraModule      infraModule;
    private MegapatchModule  megapatchModule;
    private EngagementModule engagementModule;
    private SimulationModule simulationModule;

    /** Shared "type in chat" helper for GUIs. */
    private nl.kmc.kmccore.gui.ChatInput chatInput;
    public nl.kmc.kmccore.gui.ChatInput getChatInput() { return chatInput; }

    /** Per-player language (NL/EN) — see {@code /kmclanguage}. */
    private nl.kmc.kmccore.lang.LanguageManager languageManager;
    public nl.kmc.kmccore.lang.LanguageManager getLanguageManager() { return languageManager; }

    /** Central store for tournament-presentation data (medals, future MVP/ELO/momentum). */
    private nl.kmc.kmccore.tournament.TournamentDataStore tournamentDataStore;
    public nl.kmc.kmccore.tournament.TournamentDataStore getTournamentDataStore() { return tournamentDataStore; }

    @Override public void onLoad() { instance = this; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("points.yml",   false);

        MessageUtil.init(this);

        // Enable modules in dependency order
        (coreModule       = new CoreModule(this)).enable();
        (infraModule      = new InfraModule(this)).enable();

        // Register the single, V1-backed game API. All game plugins write through
        // this (api.points()/games()...), so games and the lobby share ONE store.
        // KMCCoreV2 no longer registers a provider — this is the only one.
        try {
            nl.kmc.core.api.KMCApiProvider.set(new nl.kmc.kmccore.api.V1KMCApi(this));
            getLogger().info("[KMCCore] Registered V1-backed KMCApi (single source of truth).");
        } catch (IllegalStateException e) {
            getLogger().warning("[KMCCore] KMCApi already registered — another plugin set it first: " + e.getMessage());
        }

        (megapatchModule  = new MegapatchModule(this)).enable();
        (engagementModule = new EngagementModule(this)).enable();
        (simulationModule = new SimulationModule(this)).enable();

        // Per-player language (needs the preferences manager from MegapatchModule).
        languageManager = new nl.kmc.kmccore.lang.LanguageManager(this);
        tournamentDataStore = new nl.kmc.kmccore.tournament.TournamentDataStore(this);

        registerCommands();
        registerListeners();

        // Start background tasks after all managers are fully wired
        megapatchModule.startBackgroundTasks();
        engagementModule.startBackgroundTasks();

        // Welcome broadcast fires once when /kmctournament start runs
        WelcomeBroadcaster welcome = new WelcomeBroadcaster(this);
        getApi().onTournamentStart(welcome::broadcast);

        // Reset per-tournament presentation tallies (MVP + momentum) at start,
        // then reveal the historical power rankings.
        getApi().onTournamentStart(() -> {
            tournamentDataStore.resetTournamentMvp();
            tournamentDataStore.resetMomentum();
            nl.kmc.kmccore.tournament.PowerRankings.reveal(this);
        });

        getLogger().info("KMCCore v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        // Release the shared API slot we own.
        try { nl.kmc.core.api.KMCApiProvider.clear(); } catch (Exception ignored) {}
        // Disable in reverse dependency order
        if (simulationModule  != null) simulationModule.disable();
        if (engagementModule  != null) engagementModule.disable();
        if (megapatchModule   != null) megapatchModule.disable();
        if (infraModule       != null) infraModule.disable();
        if (coreModule        != null) coreModule.disable();
        getLogger().info("KMCCore disabled.");
    }

    // ====================================================================
    // Commands
    // ====================================================================

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
        setCmd("kmchof",         new GuiCommands.HofCommand(this));

        setCmd("kmcprefs",    new AdminCommands.PreferencesCommand(this));
        setCmd("kmchealth",   new AdminCommands.HealthCommand(this));
        setCmd("kmcready",    new AdminCommands.ReadyCommand(this));
        setCmd("kmcmap",      new AdminCommands.MapCommand(this));
        setCmd("kmclobbynpc", new AdminCommands.LobbyNPCCommand(this));

        setCmd("event",              new EventCommand(this));
        setCmd("kmcachievements",    new AchievementCommand());
        setCmd("kmcceremonies",      new CeremoniesCommand(this));
        setCmd("kmccamera",          new CameraCommand(this));
        setCmd("kmcpresentation",    new PresentationCommand(this));
        setCmd("kmcsetup",           new SetupCommand(this));
        setCmd("kmcprofile",         new GuiCommands.ProfileCommand(this));
        setCmd("kmcstandings",       new GuiCommands.StandingsCommand(this));
        setCmd("kmchelp",            new GuiCommands.HelpCommand(this));
        setCmd("kmcsettings",        new GuiCommands.SettingsCommand(this));
        setCmd("kmclanguage",        new GuiCommands.LanguageCommand(this));
        setCmd("kmcmedals",          new GuiCommands.MedalsCommand(this));
        setCmd("kmcmvp",             new GuiCommands.MvpCommand(this));
        setCmd("kmcmomentum",        new GuiCommands.MomentumCommand(this));
        setCmd("kmcpowerrank",       new GuiCommands.PowerRankCommand(this));
        setCmd("kmcwinner",          new GuiCommands.WinnerCeremonyCommand(this));
        setCmd("kmcvalidate",        new ValidateCommand(this));
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

    // ====================================================================
    // Listeners
    // ====================================================================

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinQuitListener(this),  this);
        pm.registerEvents(new ChatListener(this),            this);
        pm.registerEvents(new PlayerKillListener(this),      this);
        pm.registerEvents(new VoteListener(this),            this);
        pm.registerEvents(infraModule.getVoteGuiListener(),  this);
        pm.registerEvents(new LobbyProtectionListener(this), this);
        pm.registerEvents(new DeathListener(this),           this);
        pm.registerEvents(new GlobalPvPListener(this),       this);

        LobbyArmorListener lobbyArmor = new LobbyArmorListener(this);
        pm.registerEvents(lobbyArmor, this);
        lobbyArmor.applyToAllOnline();

        pm.registerEvents(engagementModule.getStatsGUI(),      this);
        pm.registerEvents(engagementModule.getHoFNpcManager(), this);
        pm.registerEvents(new GuiListener(), this);
        chatInput = new nl.kmc.kmccore.gui.ChatInput(this);
        pm.registerEvents(chatInput, this);
    }

    // ====================================================================
    // Static accessor
    // ====================================================================

    public static KMCCore getInstance() { return instance; }

    // ====================================================================
    // Getters — delegate to modules (public API unchanged)
    // ====================================================================

    // Core
    public DatabaseManager   getDatabaseManager()   { return coreModule.getDatabaseManager(); }
    public TeamManager       getTeamManager()       { return coreModule.getTeamManager(); }
    public PlayerDataManager getPlayerDataManager() { return coreModule.getPlayerDataManager(); }
    public PointsManager     getPointsManager()     { return coreModule.getPointsManager(); }
    public KMCApi            getApi()               { return coreModule.getApi(); }

    // Infra
    public TournamentManager getTournamentManager() { return infraModule.getTournamentManager(); }
    public GameManager       getGameManager()       { return infraModule.getGameManager(); }
    public SchematicManager  getSchematicManager()  { return infraModule.getSchematicManager(); }
    public CeremonyManager   getCeremonyManager()   { return infraModule.getCeremonyManager(); }
    public CinematicManager  getCinematicManager()  { return infraModule.getCinematicManager(); }
    public ArenaManager      getArenaManager()      { return infraModule.getArenaManager(); }
    public TabListManager    getTabListManager()    { return infraModule.getTabListManager(); }
    public ScoreboardManager getScoreboardManager() { return infraModule.getScoreboardManager(); }
    public NPCManager        getNpcManager()        { return infraModule.getNpcManager(); }
    public HallOfFameManager getHallOfFameManager() { return infraModule.getHallOfFameManager(); }
    public AutomationManager getAutomationManager() { return infraModule.getAutomationManager(); }
    public VoteGuiListener   getVoteGuiListener()   { return infraModule.getVoteGuiListener(); }

    // Megapatch
    public DiscordWebhook            getDiscordHook()        { return megapatchModule.getDiscordHook(); }
    public PlayerPreferencesManager  getPlayerPreferences()  { return megapatchModule.getPlayerPreferences(); }
    public MapRotationManager        getMapRotation()        { return megapatchModule.getMapRotation(); }
    public SpectatorManager          getSpectatorManager()   { return megapatchModule.getSpectatorManager(); }
    public LobbyNPCManager           getLobbyNPCManager()    { return megapatchModule.getLobbyNPCManager(); }
    public BossBarLeaderboardManager getBossBarLeaderboard() { return megapatchModule.getBossBarLeaderboard(); }
    public ReadyUpManager            getReadyUpManager()     { return megapatchModule.getReadyUpManager(); }
    public HealthMonitor             getHealthMonitor()      { return megapatchModule.getHealthMonitor(); }

    // Engagement
    public AchievementManager       getAchievementManager()       { return engagementModule.getAchievementManager(); }
    public TournamentHistoryManager getTournamentHistoryManager() { return engagementModule.getTournamentHistoryManager(); }
    public HoFNpcManager            getHoFNpcManager()            { return engagementModule.getHoFNpcManager(); }
    public StatsGUI                 getStatsGUI()                 { return engagementModule.getStatsGUI(); }
    public AchievementService       getAchievementServiceV2()     { return engagementModule.getAchievementServiceV2(); }

    // Simulation
    public SnapshotManager  getSnapshotManager()  { return simulationModule.getSnapshotManager(); }
    public SimulationEngine getSimulationEngine() { return simulationModule.getSimulationEngine(); }
}
