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
    @Override protected String  description() { return "Zweef door boost-ringen en bereik de finishlijn."; }
    @Override protected String  objective()   { return "Vlieg in volgorde door alle checkpoints — snelste wint."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+ptn — Per checkpoint (varieert)",
                "+200 ptn — Finishbonus",
                "+500 ptn — 1e plaats"
        );
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        courseManager = new CourseManager(this);
        elytraV2      = new ElytraEndriumGameManagerV2(this, reg, stats);
        return elytraV2;
    }

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (courseManager == null) return java.util.List.of();
        var cm = courseManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();

        boolean launch = cm.getLaunchSpawn() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Launch pad",
                launch ? "✓ ingesteld" : "niet ingesteld", launch, org.bukkit.Material.FIREWORK_ROCKET,
                p -> { cm.setLaunchSpawn(p.getLocation()); p.sendMessage("§a[Setup] Launch pad gezet op jouw locatie."); },
                "Klik: zet het launch-punt op jouw locatie"));

        int cps = cm.getCheckpoints().size();
        s.add(nl.kmc.core.setup.SetupStep.info("Checkpoints",
                cps + " (voeg toe met /ee cp <naam>)", cps > 0, org.bukkit.Material.END_ROD));
        return s;
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
