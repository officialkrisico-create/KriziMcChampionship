package nl.kmc.skywars;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.skywars.commands.SkyWarsCommand;
import nl.kmc.skywars.listeners.SkyWarsListener;
import nl.kmc.skywars.managers.ArenaManager;
import nl.kmc.skywars.managers.ChestStocker;
import nl.kmc.skywars.managers.SkyWarsGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

/**
 * Team SkyWars — PvP minigame on floating islands.
 * Registered with both V1 KMCCore (backward compat) and V2 tournament engine.
 */
public final class SkyWarsPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "team_skywars";

    private ArenaManager         arenaManager;
    private ChestStocker         chestStocker;
    private SkyWarsGameManagerV2 skyWarsGameManagerV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "Team SkyWars"; }
    @Override protected Material icon()       { return Material.ENDER_CHEST; }
    @Override protected int     minPlayers()  { return 4; }
    @Override protected String  description() { return "PvP on floating islands — last team standing wins."; }
    @Override protected String  objective()   { return "Eliminate all other teams from their islands."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+50 pts — Kill",
            "+500 pts — 1st Place",
            "+5 pts — Survival bonus (per enemy death)"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager         = new ArenaManager(this);
        chestStocker         = new ChestStocker(this);
        skyWarsGameManagerV2 = new SkyWarsGameManagerV2(this, reg, stats);
        return skyWarsGameManagerV2;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new SkyWarsCommand(this);
        var bukkitCmd = getCommand("skywars");
        if (bukkitCmd != null) {
            bukkitCmd.setExecutor(cmd);
            bukkitCmd.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new SkyWarsListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (skyWarsGameManagerV2 != null && skyWarsGameManagerV2.isRunning()) skyWarsGameManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        // No V1 game manager — V1 path is a no-op after migration.
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ArenaManager          getArenaManager()    { return arenaManager; }
    public ChestStocker          getChestStocker()    { return chestStocker; }
    public SkyWarsGameManagerV2  getSkyWarsGameManagerV2() { return skyWarsGameManagerV2; }
}
