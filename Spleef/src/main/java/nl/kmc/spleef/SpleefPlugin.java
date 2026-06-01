package nl.kmc.spleef;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.spleef.commands.SpleefCommand;
import nl.kmc.spleef.listeners.SpleefListener;
import nl.kmc.spleef.managers.ArenaManager;
import nl.kmc.spleef.managers.FloorManager;
import nl.kmc.spleef.managers.PowerupSpawner;
import nl.kmc.spleef.managers.SpleefGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class SpleefPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "spleef_teams";

    private ArenaManager      arenaManager;
    private FloorManager      floorManager;
    private PowerupSpawner    powerupSpawner;
    private SpleefGameManagerV2 spleefGameManagerV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Spleef"; }
    @Override protected Material icon()        { return Material.DIAMOND_SHOVEL; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Break the floor under opponents to eliminate them."; }
    @Override protected String   objective()   { return "Be the last player standing on the snow floor."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+2 pts — Block broken",
            "+35 pts — Elimination",
            "+5 pts — Survival bonus (per elimination)"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager      = new ArenaManager(this);
        floorManager      = new FloorManager(this);
        powerupSpawner    = new PowerupSpawner(this);
        spleefGameManagerV2 = new SpleefGameManagerV2(this, reg, stats);
        return spleefGameManagerV2;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new SpleefCommand(this);
        var bukkitCmd = getCommand("spleef");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new SpleefListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (spleefGameManagerV2 != null && spleefGameManagerV2.isRunning()) spleefGameManagerV2.end();
        if (floorManager        != null) floorManager.cancelTasks();
        if (powerupSpawner      != null) powerupSpawner.stop();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ArenaManager       getArenaManager()    { return arenaManager; }
    public FloorManager       getFloorManager()    { return floorManager; }
    public PowerupSpawner     getPowerupSpawner()  { return powerupSpawner; }
    public SpleefGameManagerV2 getSpleefGameManagerV2() { return spleefGameManagerV2; }
}
