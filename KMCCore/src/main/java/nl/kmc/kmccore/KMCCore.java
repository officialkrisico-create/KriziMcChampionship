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
    private HallOfFameManager  hallOfFameManager;
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

        registerCommands();
        registerListeners();

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
        setCmd("kmchof",         new HoFCommand(this));
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
    public HallOfFameManager  getHallOfFameManager() { return hallOfFameManager; }
    public VoteGuiListener    getVoteGuiListener()   { return voteGuiListener; }
    public KMCApi             getApi()               { return api; }
}
