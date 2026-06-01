package nl.kmc.tgttos;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.tgttos.commands.TGTTOSCommand;
import nl.kmc.tgttos.listeners.MovementListener;
import nl.kmc.tgttos.managers.MapManager;
import nl.kmc.tgttos.managers.TGTTOSGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class TGTTOSPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "tgttos";

    private MapManager          mapManager;
    private TGTTOSGameManagerV2 tgttosGameManagerV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "TGTTOS"; }
    @Override protected Material icon()        { return Material.DIRT_PATH; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Race from one side of the map to the other on rotating maps."; }
    @Override protected String   objective()   { return "Finish each round as fast as possible."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+100 pts — 1st finish in round",
            "+75 pts — 2nd finish",
            "+5 pts — DNF consolation"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        mapManager          = new MapManager(this);
        tgttosGameManagerV2 = new TGTTOSGameManagerV2(this, reg, stats);
        return tgttosGameManagerV2;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new TGTTOSCommand(this);
        var bukkitCmd = getCommand("tgttos");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (tgttosGameManagerV2 != null && tgttosGameManagerV2.isRunning()) tgttosGameManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public MapManager          getMapManager()         { return mapManager; }
    public TGTTOSGameManagerV2 getTGTTOSGameManagerV2(){ return tgttosGameManagerV2; }
}
