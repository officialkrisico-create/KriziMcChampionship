package nl.kmc.kmccore.module;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.listeners.VoteGuiListener;
import nl.kmc.kmccore.managers.*;
import nl.kmc.kmccore.npc.NPCManager;
import nl.kmc.kmccore.scoreboard.ScoreboardManager;

/**
 * Core game-flow and display infrastructure.
 *
 * <p>Depends on {@link CoreModule} being enabled first.
 */
public class InfraModule implements PluginModule {

    private final KMCCore plugin;

    private TournamentManager tournamentManager;
    private GameManager       gameManager;
    private SchematicManager  schematicManager;
    private ArenaManager      arenaManager;
    private TabListManager    tabListManager;
    private ScoreboardManager scoreboardManager;
    private NPCManager        npcManager;
    private HallOfFameManager hallOfFameManager;
    private AutomationManager automationManager;
    private VoteGuiListener   voteGuiListener;

    public InfraModule(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public void enable() {
        tournamentManager = new TournamentManager(plugin);
        gameManager       = new GameManager(plugin);
        schematicManager  = new SchematicManager(plugin);
        arenaManager      = new ArenaManager(plugin);
        tabListManager    = new TabListManager(plugin);
        scoreboardManager = new ScoreboardManager(plugin);
        npcManager        = new NPCManager(plugin);
        hallOfFameManager = new HallOfFameManager(plugin);
        automationManager = new AutomationManager(plugin);
        voteGuiListener   = new VoteGuiListener(plugin);
    }

    @Override
    public void disable() {
        if (tournamentManager != null) tournamentManager.save();
        if (gameManager       != null) gameManager.save();
        if (automationManager != null) automationManager.stop();
        if (scoreboardManager != null) scoreboardManager.cleanup();
        if (npcManager        != null) npcManager.save();
    }

    public TournamentManager getTournamentManager() { return tournamentManager; }
    public GameManager       getGameManager()       { return gameManager; }
    public SchematicManager  getSchematicManager()  { return schematicManager; }
    public ArenaManager      getArenaManager()      { return arenaManager; }
    public TabListManager    getTabListManager()    { return tabListManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public NPCManager        getNpcManager()        { return npcManager; }
    public HallOfFameManager getHallOfFameManager() { return hallOfFameManager; }
    public AutomationManager getAutomationManager() { return automationManager; }
    public VoteGuiListener   getVoteGuiListener()   { return voteGuiListener; }
}
