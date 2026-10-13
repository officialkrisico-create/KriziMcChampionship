package nl.kmc.formula;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.formula.commands.FmcCommand;
import nl.kmc.formula.listeners.RaceListener;
import nl.kmc.formula.managers.CircuitManager;
import nl.kmc.formula.managers.FormulaGameManagerV2;
import nl.kmc.formula.models.Circuit;
import nl.kmc.formula.models.Gate;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.TestArenaKit;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/** Formula MC — boat racing on a hand-built ice circuit. */
public final class FormulaMCPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "formula_mc";

    private CircuitManager circuitManager;
    private FormulaGameManagerV2 gameManager;

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Formula MC"; }
    @Override protected Material icon()        { return Material.BLUE_ICE; }
    @Override protected int      minPlayers()  { return 1; }
    @Override protected String   description() { return "Race in een boot over een ijsbaan — meerdere rondes, geen botsingen."; }
    @Override protected String   objective()   { return "Voltooi als eerste alle rondes."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+" + getConfig().getInt("points.placement.first", 320) + " ptn voor de winnaar, daarna -"
                        + getConfig().getInt("points.placement.step", 10) + " per plaats (jouw punten tellen voor je team)",
                "+" + getConfig().getInt("points.fastest-lap-bonus", 25) + " ptn — snelste ronde van de race",
                "+" + getConfig().getInt("points.team-avg-placement.1", 20) + " / +"
                        + getConfig().getInt("points.team-avg-placement.2", 15) + " / +"
                        + getConfig().getInt("points.team-avg-placement.3", 10)
                        + " ptn voor elk lid — beste gemiddelde eindplaats van je team");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        circuitManager = new CircuitManager(this);
        gameManager = new FormulaGameManagerV2(this, reg, stats);
        return gameManager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new FmcCommand(this);
        var bukkitCmd = getCommand("fmc");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new RaceListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (gameManager != null && gameManager.isRunning()) gameManager.end();
    }

    @Override protected void onV1GameStart(String gameId) { /* V2 only */ }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * /kmctest: a rectangular ice circuit (track 8 wide, black walls), start line on the south straight,
     * three checkpoints, a 2-wide grid, 2 laps. Races run counter-clockwise.
     */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();

        // Outer 64 x 44, inner hole 48 x 28 → track width 8.
        int ox = 32, oz = 22, ix = 24, iz = 14;
        TestArenaKit.fill(w, cx - ox, y, cz - oz, cx + ox, y, cz + oz, Material.BLUE_ICE);
        TestArenaKit.fill(w, cx - ix, y, cz - iz, cx + ix, y, cz + iz, Material.AIR);
        // walls (3 high) on the outer and inner edge
        TestArenaKit.fill(w, cx - ox - 1, y + 1, cz - oz - 1, cx + ox + 1, y + 3, cz - oz - 1, Material.BLACK_CONCRETE);
        TestArenaKit.fill(w, cx - ox - 1, y + 1, cz + oz + 1, cx + ox + 1, y + 3, cz + oz + 1, Material.BLACK_CONCRETE);
        TestArenaKit.fill(w, cx - ox - 1, y + 1, cz - oz - 1, cx - ox - 1, y + 3, cz + oz + 1, Material.BLACK_CONCRETE);
        TestArenaKit.fill(w, cx + ox + 1, y + 1, cz - oz - 1, cx + ox + 1, y + 3, cz + oz + 1, Material.BLACK_CONCRETE);
        // The inner walls run along the rim of the (air) infield — a floor-level block under each so they
        // read as solid, and the track edge is sealed so a boat can never drop into the hole.
        TestArenaKit.fill(w, cx - ix, y, cz - iz, cx + ix, y + 3, cz - iz, Material.BLACK_CONCRETE);
        TestArenaKit.fill(w, cx - ix, y, cz + iz, cx + ix, y + 3, cz + iz, Material.BLACK_CONCRETE);
        TestArenaKit.fill(w, cx - ix, y, cz - iz, cx - ix, y + 3, cz + iz, Material.BLACK_CONCRETE);
        TestArenaKit.fill(w, cx + ix, y, cz - iz, cx + ix, y + 3, cz + iz, Material.BLACK_CONCRETE);
        // start/finish stripe on the south straight at x = cx
        TestArenaKit.fill(w, cx, y, cz + iz + 2, cx, y, cz + oz - 1, Material.WHITE_CONCRETE);

        Circuit c = circuitManager.get("test");
        if (c == null) c = circuitManager.create("test", "Test Circuit", w);
        c.setWorld(w);
        c.setLaps(2);
        c.getSpawns().clear();
        // South straight runs east (+x, yaw -90). Two lanes, four rows, behind the start line (x < cx).
        for (int i = 0; i < 8; i++) {
            int row = i / 2, lane = i % 2;
            c.getSpawns().add(new Location(w, cx - 4 - row * 4 + 0.5, y + 1, cz + iz + 4 + lane * 3 + 0.5, -90f, 0f));
        }
        c.setLine(new Gate(w.getName(), cx, y, cz + iz + 1, cx, y + 3, cz + oz));
        c.getCheckpoints().clear();
        c.getCheckpoints().add(new Gate(w.getName(), cx + ix + 1, y, cz, cx + ox, y + 3, cz));   // east straight
        c.getCheckpoints().add(new Gate(w.getName(), cx, y, cz - oz, cx, y + 3, cz - iz - 1));   // north straight
        c.getCheckpoints().add(new Gate(w.getName(), cx - ox, y, cz, cx - ix - 1, y + 3, cz));   // west straight
        c.setReset(new Location(w, cx - 8 + 0.5, y + 1, cz + iz + 5 + 0.5, -90f, 0f));
        circuitManager.select("test");
        circuitManager.save();
        return new Location(w, cx - 8 + 0.5, y + 4, cz + iz + 5 + 0.5, -90f, 20f);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public CircuitManager        getCircuitManager() { return circuitManager; }
    public FormulaGameManagerV2  getGameManagerV2()  { return gameManager; }
}
