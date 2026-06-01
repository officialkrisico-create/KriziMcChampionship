package nl.kmc.bridge;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.bridge.commands.BridgeCommand;
import nl.kmc.bridge.listeners.BridgeListener;
import nl.kmc.bridge.managers.ArenaManager;
import nl.kmc.bridge.managers.AssistManager;
import nl.kmc.bridge.managers.BlockTracker;
import nl.kmc.bridge.managers.BridgeGameManagerV2;
import nl.kmc.bridge.managers.KitManager;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class TheBridgePlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "the_bridge";

    private ArenaManager        arenaManager;
    private KitManager          kitManager;
    private BlockTracker        blockTracker;
    private AssistManager       assistManager;
    private BridgeGameManagerV2 bridgeGameManagerV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "The Bridge"; }
    @Override protected Material icon()        { return Material.BLUE_WOOL; }
    @Override protected int      minPlayers()  { return 4; }
    @Override protected String   description() { return "Bridge to the enemy goal and score! First team to 5 goals wins."; }
    @Override protected String   objective()   { return "Score goals by entering the opponent's goal hole."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+150 pts — Goal scored",
            "+50 pts — Kill",
            "+500 pts — 1st Place"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager        = new ArenaManager(this);
        kitManager          = new KitManager(this);
        blockTracker        = new BlockTracker(this);
        assistManager       = new AssistManager();
        bridgeGameManagerV2 = new BridgeGameManagerV2(this, reg, stats);
        return bridgeGameManagerV2;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new BridgeCommand(this);
        var bukkitCmd = getCommand("bridge");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new BridgeListener(this), this);
        getServer().getPluginManager().registerEvents(assistManager, this);
    }

    @Override
    protected void onGameDisable() {
        if (bridgeGameManagerV2 != null && bridgeGameManagerV2.isRunning()) bridgeGameManagerV2.end();
        if (blockTracker        != null) blockTracker.cancelTasks();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ArenaManager        getArenaManager()       { return arenaManager; }
    public KitManager          getKitManager()         { return kitManager; }
    public BlockTracker        getBlockTracker()       { return blockTracker; }
    public AssistManager       getAssistManager()      { return assistManager; }
    public BridgeGameManagerV2 getBridgeGameManagerV2(){ return bridgeGameManagerV2; }
}
