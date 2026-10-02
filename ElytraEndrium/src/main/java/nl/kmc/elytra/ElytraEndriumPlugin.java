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

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * /kmctest: a launch platform, then five large checkpoint rings along +X (each a little lower
     * than the last, with a respawn platform beneath), boost hoops in between; the last ring is the finish.
     */
    @Override
    protected org.bukkit.Location buildTestArena(org.bukkit.entity.Player admin, org.bukkit.Location origin) {
        var w = origin.getWorld();
        var a = nl.kmc.game.api.TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        int launchX = cx - 20;

        nl.kmc.game.api.TestArenaKit.platform(w, launchX, y - 1, cz, 4, 4, org.bukkit.Material.QUARTZ_BLOCK);
        courseManager.setCourseWorld(w);
        courseManager.setLaunchSpawn(nl.kmc.game.api.TestArenaKit.stand(w, launchX, y, cz, -90));
        courseManager.clearCheckpoints();
        for (String id : new java.util.ArrayList<>(courseManager.getBoostHoops().keySet())) courseManager.removeBoost(id);

        for (int i = 1; i <= 5; i++) {
            int rx = launchX + 35 * i;
            int ry = y - 4 * i;
            frame(w, rx, ry, cz, 6, org.bukkit.Material.SEA_LANTERN);
            // Respawn platform below the ring
            nl.kmc.game.api.TestArenaKit.platform(w, rx, ry - 8, cz, 2, 2, org.bukkit.Material.QUARTZ_BLOCK);
            courseManager.addOrUpdateCheckpoint(i, i == 5 ? "Finish" : "Ring " + i,
                    new org.bukkit.Location(w, rx, ry - 6, cz - 6), new org.bukkit.Location(w, rx + 1, ry + 6, cz + 6),
                    nl.kmc.game.api.TestArenaKit.stand(w, rx, ry - 7, cz, -90), 50);

            if (i < 5) {
                int bx = rx + 17, by = ry - 2;
                frame(w, bx, by, cz, 4, org.bukkit.Material.GOLD_BLOCK);
                courseManager.addOrUpdateBoost(new nl.kmc.elytra.models.BoostHoop("boost" + i,
                        new org.bukkit.Location(w, bx, by - 4, cz - 4), new org.bukkit.Location(w, bx, by + 4, cz + 4), 1.4));
            }
        }
        return nl.kmc.game.api.TestArenaKit.stand(w, launchX, y, cz, -90);
    }

    /** A hollow square frame ({@code half}*2+1 wide) standing upright at x, centred on (y, cz). */
    private static void frame(org.bukkit.World w, int x, int y, int cz, int half, org.bukkit.Material m) {
        nl.kmc.game.api.TestArenaKit.fill(w, x, y - half, cz - half, x, y - half, cz + half, m);
        nl.kmc.game.api.TestArenaKit.fill(w, x, y + half, cz - half, x, y + half, cz + half, m);
        nl.kmc.game.api.TestArenaKit.fill(w, x, y - half, cz - half, x, y + half, cz - half, m);
        nl.kmc.game.api.TestArenaKit.fill(w, x, y - half, cz + half, x, y + half, cz + half, m);
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
