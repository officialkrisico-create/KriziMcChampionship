package nl.kmc.elytra;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.elytra.commands.ElytraCommand;
import nl.kmc.elytra.listeners.MovementListener;
import nl.kmc.elytra.managers.CourseManager;
import nl.kmc.elytra.managers.ElytraEndriumGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;

public final class ElytraEndriumPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "elytra_endrium";

    private CourseManager              courseManager;
    private ElytraEndriumGameManagerV2 elytraV2;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String  gameId()      { return GAME_ID; }
    @Override protected String  displayName() { return "Elytra Endrium"; }
    @Override protected Material icon()       { return Material.ELYTRA; }
    @Override protected int     minPlayers()  { return 2; }
    @Override protected String  description() { return "Glide through boost hoops and reach the finish line."; }
    @Override protected String  objective()   { return "Fly through all checkpoints in order — fastest wins."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+pts — Per checkpoint (varies)",
                "+200 pts — Finish bonus",
                "+500 pts — 1st Place"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        courseManager = new CourseManager(this);
        elytraV2      = new ElytraEndriumGameManagerV2(this, reg, stats);
        return elytraV2;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new ElytraCommand(this);
        var bukkitCmd = getCommand("elytraendrium");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (elytraV2 != null && elytraV2.isRunning()) elytraV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().warning("[Elytra] V1 auto-start fired but V1 GameManager has been removed.");
            if (kmcCore.getAutomationManager().isRunning())
                kmcCore.getAutomationManager().onGameEnd(null);
        }, 40L);
    }

    // ── Getters used by commands / listeners ──────────────────────────────────

    public KMCCore                    getKmcCore()       { return kmcCore; }
    public CourseManager              getCourseManager() { return courseManager; }
    public ElytraEndriumGameManagerV2 getGameManagerV2() { return elytraV2; }
}
