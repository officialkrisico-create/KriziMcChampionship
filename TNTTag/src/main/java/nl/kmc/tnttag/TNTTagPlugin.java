package nl.kmc.tnttag;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.tnttag.commands.TagCommand;
import nl.kmc.tnttag.listeners.TagListener;
import nl.kmc.tnttag.managers.ArenaManager;
import nl.kmc.tnttag.managers.TNTTagGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class TNTTagPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "tnt_tag";

    private ArenaManager        arenaManager;
    private TNTTagGameManagerV2 tntManagerV2;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String      gameId()      { return GAME_ID; }
    @Override protected String      displayName() { return "TNT Tag"; }
    @Override protected Material    icon()        { return Material.TNT; }
    @Override protected int         minPlayers()  { return 4; }
    @Override protected String      description() { return "Don't be the one holding the bomb when time runs out!"; }
    @Override protected String      objective()   { return "Survive all rounds without getting eliminated."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+20 pts — Survive a round",
            "+500 pts — 1st Place"
        );
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arenaManager = new ArenaManager(this);
        tntManagerV2 = new TNTTagGameManagerV2(this, reg, stats);
        return tntManagerV2;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onGameEnable() {
        if (arenaManager == null) arenaManager = new ArenaManager(this);

        var cmd = new TagCommand(this);
        var bukkitCmd = getCommand("tnttag");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new TagListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (tntManagerV2 != null && tntManagerV2.isRunning()) tntManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        getLogger().warning("[TNTTag] V1 game-start signal received but V1 GameManager has been removed. Use V2.");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public ArenaManager        getArenaManager()  { return arenaManager; }
    /** Returns the V2 manager cast to TNTTagGameManagerV2, or null. */
    public TNTTagGameManagerV2 getTntManagerV2()  { return tntManagerV2; }
}
