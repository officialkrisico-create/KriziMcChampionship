package nl.kmc.parkour;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.parkour.commands.ParkourCommand;
import nl.kmc.parkour.listeners.MovementListener;
import nl.kmc.parkour.managers.CourseManager;
import nl.kmc.parkour.managers.ParkourGameManagerV2;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Material;

import java.util.List;

public final class ParkourWarriorPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "parkour_warrior";

    private CourseManager        courseManager;
    private ParkourGameManagerV2 parkourManagerV2;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String      gameId()      { return GAME_ID; }
    @Override protected String      displayName() { return "Parkour Warrior"; }
    @Override protected Material    icon()        { return Material.FEATHER; }
    @Override protected int         minPlayers()  { return 2; }
    @Override protected String      description() { return "Race zo snel mogelijk door de parkour-checkpoints."; }
    @Override protected String      objective()   { return "Bereik de meeste checkpoints voordat de tijd om is."; }
    @Override protected List<String> scoringLines() {
        return List.of(
            "+25 ptn — Checkpoint bereikt",
            "+200 ptn — Parcours voltooid",
            "+500 ptn — 1e plaats"
        );
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        courseManager    = new CourseManager(this);
        parkourManagerV2 = new ParkourGameManagerV2(this, reg, stats);
        return parkourManagerV2;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected java.util.List<nl.kmc.core.setup.SetupStep> extraSetupSteps(org.bukkit.entity.Player viewer) {
        if (courseManager == null) return java.util.List.of();
        var cm = courseManager;
        java.util.List<nl.kmc.core.setup.SetupStep> s = new java.util.ArrayList<>();

        boolean start = cm.getStartSpawn() != null;
        s.add(nl.kmc.core.setup.SetupStep.action("Start",
                start ? "✓ ingesteld" : "niet ingesteld", start, org.bukkit.Material.LIME_BANNER,
                p -> { cm.setStartSpawn(p.getLocation()); p.sendMessage("§a[Setup] Start gezet op jouw locatie."); },
                "Klik: zet het startpunt op jouw locatie"));

        int cps = cm.getCheckpointCount();
        s.add(nl.kmc.core.setup.SetupStep.info("Checkpoints",
                cps + " (voeg toe met /pkw cp <naam>)", cps > 0, org.bukkit.Material.END_ROD));
        return s;
    }

    @Override
    protected void onGameEnable() {
        if (courseManager == null) courseManager = new CourseManager(this);

        ParkourCommand cmd = new ParkourCommand(this);
        var bukkitCmd = getCommand("parkourwarrior");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);
    }

    @Override protected boolean supportsTestArena() { return true; }

    /** /kmctest: a start platform plus 7 floating 4x4 stage platforms in a line (3-block gaps), the last one is the finish. */
    @Override
    protected org.bukkit.Location buildTestArena(org.bukkit.entity.Player admin, org.bukkit.Location origin) {
        var w = origin.getWorld();
        var a = nl.kmc.game.api.TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        int x0 = cx - 25;

        courseManager.setCourseWorld(w);
        courseManager.clearCheckpoints();
        for (int i = 0; i <= 7; i++) {
            int px = x0 + i * 7;
            org.bukkit.Material m = i == 0 ? org.bukkit.Material.LIME_CONCRETE
                    : i == 7 ? org.bukkit.Material.GOLD_BLOCK
                    : (i % 2 == 0 ? org.bukkit.Material.WHITE_CONCRETE : org.bukkit.Material.LIGHT_GRAY_CONCRETE);
            nl.kmc.game.api.TestArenaKit.fill(w, px, y, cz - 1, px + 3, y, cz + 2, m);
            var onPlatform = nl.kmc.game.api.TestArenaKit.stand(w, px + 1, y + 1, cz, -90);
            if (i == 0) {
                courseManager.setStartSpawn(onPlatform);
            } else {
                courseManager.addOrUpdateCheckpoint(i, "Stage " + i,
                        new org.bukkit.Location(w, px, y + 1, cz - 1),
                        new org.bukkit.Location(w, px + 3, y + 3, cz + 2), onPlatform, 25);
            }
        }
        return nl.kmc.game.api.TestArenaKit.stand(w, x0 + 1, y + 1, cz, -90);
    }

    @Override
    protected void onGameDisable() {
        if (parkourManagerV2 != null && parkourManagerV2.isRunning()) parkourManagerV2.end();
    }

    @Override
    protected void onV1GameStart(String gameId) {
        getLogger().warning("[Parkour] V1 game-start signal received but V1 GameManager has been removed. Use V2.");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public CourseManager        getCourseManager()    { return courseManager; }
    /** Returns the V2 manager cast to ParkourGameManagerV2, or null. */
    public ParkourGameManagerV2 getParkourManagerV2() { return parkourManagerV2; }
}
