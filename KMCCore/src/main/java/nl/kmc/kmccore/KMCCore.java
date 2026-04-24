package nl.kmc.kmccore;

import nl.kmc.kmccore.api.KMCApi;
import nl.kmc.kmccore.commands.*;
import nl.kmc.kmccore.database.DatabaseManager;
import nl.kmc.kmccore.listeners.*;
import nl.kmc.kmccore.managers.*;
import nl.kmc.kmccore.npc.NPCManager;
import nl.kmc.kmccore.scoreboard.ScoreboardManager;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class KMCCore extends JavaPlugin {

    private static KMCCore instance;

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
    private VoteGuiListener    voteGuiListener;

    private KMCApi api;

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
        tournamentManager = new TournamentManager(this);
        gameManager       = new GameManager(this);
        schematicManager  = new SchematicManager(this);
        arenaManager      = new ArenaManager(this);
        tabListManager    = new TabListManager(this);
        scoreboardManager = new ScoreboardManager(this);
        npcManager        = new NPCManager(this);
        automationManager = new AutomationManager(this);

        voteGuiListener = new VoteGuiListener(this);

        registerCommands();
        registerListeners();

        api = new KMCApi(this);

        getLogger().info("KMCCore v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
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
        getCommand("kmcteam").setExecutor(new TeamCommand(this));
        getCommand("kmcteam").setTabCompleter(new TeamCommand(this));
        getCommand("kmcstats").setExecutor(new StatsCommand(this));
        getCommand("kmctournament").setExecutor(new TournamentCommand(this));
        getCommand("kmcgame").setExecutor(new GameCommand(this));
        getCommand("kmcgame").setTabCompleter(new GameCommand(this));
        getCommand("kmclb").setExecutor(new LeaderboardCommand(this));
        getCommand("kmcnpc").setExecutor(new NPCCommand(this));
        getCommand("kmcround").setExecutor(new RoundCommand(this));
        getCommand("kmcpoints").setExecutor(new PointsCommand(this));
        getCommand("kmcpoints").setTabCompleter(new PointsCommand(this));
        getCommand("tc").setExecutor(new TeamChatCommand(this));
        getCommand("kmcvote").setExecutor(new VoteCommand(this));
        getCommand("kmcauto").setExecutor(new AutomationCommand(this));
        getCommand("kmcauto").setTabCompleter(new AutomationCommand(this));
        getCommand("kmcarena").setExecutor(new ArenaCommand(this));
        getCommand("kmcarena").setTabCompleter(new ArenaCommand(this));
        getCommand("kmclobby").setExecutor(new LobbyCommand(this));
        getCommand("kmcrandomteams").setExecutor(new RandomTeamsCommand(this));
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this),            this);
        getServer().getPluginManager().registerEvents(new PlayerKillListener(this),      this);
        getServer().getPluginManager().registerEvents(new VoteListener(this),            this);
        getServer().getPluginManager().registerEvents(voteGuiListener,                    this);
        getServer().getPluginManager().registerEvents(new LobbyProtectionListener(this), this);
    }

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
    public VoteGuiListener    getVoteGuiListener()   { return voteGuiListener; }
    public KMCApi             getApi()               { return api; }
}
