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

    /** Book of every point award (score breakdown, awards, statsbook). */
    private nl.kmc.kmccore.scoring.PointsLedger pointsLedger;
    public nl.kmc.kmccore.scoring.PointsLedger getPointsLedger() { return pointsLedger; }

    /** Kill-cam / finish-cam. */
    private nl.kmc.kmccore.presentation.CamService camService;
    public nl.kmc.kmccore.presentation.CamService getCamService() { return camService; }

    /** The GG button after a game. */
    private nl.kmc.kmccore.presentation.GgManager ggManager;
    public nl.kmc.kmccore.presentation.GgManager getGgManager() { return ggManager; }
    private nl.kmc.kmccore.platform.ClipMarkers clipMarkers;
    private nl.kmc.kmccore.platform.LiveApiServer liveApi;
    private nl.kmc.kmccore.platform.SafeTeleportGuard safeTeleportGuard;
    private nl.kmc.kmccore.platform.TeamEmblems teamEmblems;
    private nl.kmc.kmccore.platform.StoryManager storyManager;
    private nl.kmc.kmccore.presentation.camera.SplineRecorder splineRecorder;
    public nl.kmc.kmccore.platform.ClipMarkers getClipMarkers() { return clipMarkers; }
    public nl.kmc.kmccore.platform.LiveApiServer getLiveApi() { return liveApi; }
    public nl.kmc.kmccore.platform.SafeTeleportGuard getSafeTeleportGuard() { return safeTeleportGuard; }
    public nl.kmc.kmccore.platform.TeamEmblems getTeamEmblems() { return teamEmblems; }
    public nl.kmc.kmccore.presentation.camera.SplineRecorder getSplineRecorder() { return splineRecorder; }
    private nl.kmc.kmccore.lobbygames.LobbyGamesManager lobbyGames;
    private nl.kmc.kmccore.lobbygames.LobbyGamesListener lobbyGamesListener;
    public nl.kmc.kmccore.lobbygames.LobbyGamesManager getLobbyGames() { return lobbyGames; }

    /** Lobby presenter that announces games, results and the finale. */
    private nl.kmc.kmccore.npc.PresenterManager presenter;
    public nl.kmc.kmccore.npc.PresenterManager getPresenter() { return presenter; }

    /** Live player ranking on NPCs (/kmcnpc rank ...). */
    private nl.kmc.kmccore.npc.RankNpcManager rankNpcManager;
    public nl.kmc.kmccore.npc.RankNpcManager getRankNpcManager() { return rankNpcManager; }

    /** Closes player chat while something is explained or celebrated. */
    private nl.kmc.kmccore.presentation.ChatGate chatGate;
    public nl.kmc.kmccore.presentation.ChatGate getChatGate() { return chatGate; }

    /** All-time track records and personal bests (Track Mastery). */
    private nl.kmc.kmccore.scoring.TrackRecords trackRecords;
    public nl.kmc.kmccore.scoring.TrackRecords getTrackRecords() { return trackRecords; }

    /** Event-director tools: presets, dry-run, emergency freeze. */
    private nl.kmc.kmccore.director.DirectorService director;
    public nl.kmc.kmccore.director.DirectorService getDirector() { return director; }

    /** Post-tournament "Fan Favorite" crowd vote. */
    private nl.kmc.kmccore.tournament.FanFavoriteManager fanFavoriteManager;
    public nl.kmc.kmccore.tournament.FanFavoriteManager getFanFavoriteManager() { return fanFavoriteManager; }

    @Override public void onLoad() { instance = this; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pointsLedger = new nl.kmc.kmccore.scoring.PointsLedger(this);
        trackRecords = new nl.kmc.kmccore.scoring.TrackRecords(this);
        chatGate = new nl.kmc.kmccore.presentation.ChatGate(this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            pointsLedger.flush();
            trackRecords.flush();
        }, 600L, 600L);
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
        fanFavoriteManager = new nl.kmc.kmccore.tournament.FanFavoriteManager(this);
        director = new nl.kmc.kmccore.director.DirectorService(this);
        rankNpcManager = new nl.kmc.kmccore.npc.RankNpcManager(this);
        presenter = new nl.kmc.kmccore.npc.PresenterManager(this);
        camService = new nl.kmc.kmccore.presentation.CamService(this);
        ggManager = new nl.kmc.kmccore.presentation.GgManager(this);
        clipMarkers = new nl.kmc.kmccore.platform.ClipMarkers(this);
        safeTeleportGuard = new nl.kmc.kmccore.platform.SafeTeleportGuard(this);
        teamEmblems = new nl.kmc.kmccore.platform.TeamEmblems(this);
        storyManager = new nl.kmc.kmccore.platform.StoryManager(this);
        splineRecorder = new nl.kmc.kmccore.presentation.camera.SplineRecorder(this);
        liveApi = new nl.kmc.kmccore.platform.LiveApiServer(this);
        var platformPm = getServer().getPluginManager();
        platformPm.registerEvents(clipMarkers, this);
        platformPm.registerEvents(new nl.kmc.kmccore.lobbygames.LobbyArcadeListener(lobbyGames), this);
        platformPm.registerEvents(safeTeleportGuard, this);
        platformPm.registerEvents(storyManager, this);
        platformPm.registerEvents(new nl.kmc.kmccore.platform.SpectatorAutoHook(this), this);
        platformPm.registerEvents(new nl.kmc.kmccore.platform.LobbyAntiGriefListener(this), this);
        liveApi.start();
        lobbyGames = new nl.kmc.kmccore.lobbygames.LobbyGamesManager(this);
        lobbyGamesListener = new nl.kmc.kmccore.lobbygames.LobbyGamesListener(this, lobbyGames);
        getServer().getPluginManager().registerEvents(lobbyGamesListener, this);
        // The MCC tab layout shows the live points of the running game, so it keeps moving.
        getServer().getScheduler().runTaskTimer(this, () -> {
            try { getTabListManager().refreshLive(); } catch (Throwable ignored) { }
        }, 100L, 40L);

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

        lobbyMvpCrown = new nl.kmc.kmccore.lobby.LobbyMvpCrown(this);
        lobbyMvpCrown.start();

        // Per-game camera setup: "/<game> cam" (see GameApiPlugin) opens this menu; the wand adds waypoints.
        getServer().getPluginManager().registerEvents(new nl.kmc.kmccore.listeners.CameraWandListener(this), this);
        getServer().getPluginManager().registerEvents(new nl.kmc.kmccore.listeners.FireworkSafetyListener(), this);

        getLogger().info("KMCCore v" + getDescription().getVersion() + " enabled!");
    }

    private nl.kmc.kmccore.lobby.LobbyMvpCrown lobbyMvpCrown;
    private nl.kmc.kmccore.presentation.CeremonyFireworks ceremonyFireworks;

    /** Configurable opening-ceremony fireworks (/kmcopeningsceremony). */
    public nl.kmc.kmccore.presentation.CeremonyFireworks getCeremonyFireworks() {
        if (ceremonyFireworks == null) ceremonyFireworks = new nl.kmc.kmccore.presentation.CeremonyFireworks(this);
        return ceremonyFireworks;
    }

    /** Opens the camera setup menu of one game ({@code /<game> cam}). */
    public void openGameCamera(org.bukkit.entity.Player admin, String gameId, String gameName) {
        new nl.kmc.kmccore.gui.GameCameraGui(this, gameId, gameName, admin).open(admin);
    }

    @Override
    public void onDisable() {
        if (lobbyMvpCrown != null) lobbyMvpCrown.stop();
        if (rankNpcManager != null) rankNpcManager.shutdown();
        if (lobbyGames != null) lobbyGames.shutdown();
        if (liveApi != null) liveApi.stop();
        if (splineRecorder != null) splineRecorder.shutdown();
        if (presenter != null) presenter.shutdown();
        if (pointsLedger != null) pointsLedger.flush();
        if (trackRecords != null) trackRecords.flush();
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
        setCmd("pause",          new nl.kmc.kmccore.commands.DirectorCommands.PauseCommand(this));
        setCmd("resume",         new nl.kmc.kmccore.commands.DirectorCommands.ResumeCommand(this));
        setCmd("kmcdirector",    new nl.kmc.kmccore.commands.DirectorCommands.DirectorCommand(this));
        setCmd("gg",             new nl.kmc.kmccore.commands.HistoryCommands.GgCommand(this));
        var lobbyGamesCmd = new nl.kmc.kmccore.lobbygames.LobbyGamesCommand(this, lobbyGames, lobbyGamesListener);
        setCmd("lobbyspleef",    lobbyGamesCmd);
        var platformCmd = new nl.kmc.kmccore.platform.PlatformCommands(this);
        for (String c : new String[] {"clip", "kmcaudit", "kmcapi", "kmchud", "kmcemblem", "teamprofile", "kmcspec"})
            setCmd(c, platformCmd);
        setCmd("ttt",            lobbyGamesCmd);
        setCmd("darts",          lobbyGamesCmd);
        setCmd("lobbygames",     lobbyGamesCmd);
        setCmd("kmcseason",      new nl.kmc.kmccore.commands.HistoryCommands.SeasonCommand(this));
        setCmd("kmctrack",       new nl.kmc.kmccore.commands.HistoryCommands.TrackCommand(this));
        setCmd("kmcfeedback",    new nl.kmc.kmccore.commands.FeedbackCommand(this));
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
        setCmd("kmcopeningsceremony", new nl.kmc.kmccore.commands.OpeningCeremonyCommand(this));
        setCmd("kmcpresentation",    new PresentationCommand(this));
        setCmd("kmcsetup",           new SetupCommand(this));
        setCmd("tutorial",           new GuiCommands.TutorialCommand(this));
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
        pm.registerEvents(new nl.kmc.kmccore.presentation.MomentCommentary(this), this);

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
