package nl.kmc.inferno;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.TestArenaKit;
import nl.kmc.inferno.commands.InfernoCommand;
import nl.kmc.inferno.listeners.InfernoListener;
import nl.kmc.inferno.managers.InfernoArena;
import nl.kmc.inferno.managers.RisingInfernoGameManagerV2;
import nl.kmc.inferno.model.Box;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class RisingInfernoPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "rising_inferno";

    private InfernoArena arena;
    private RisingInfernoGameManagerV2 gameManager;

    // ── AbstractGamePlugin metadata ───────────────────────────────────────────

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Rising Inferno"; }
    @Override protected Material icon()        { return Material.LAVA_BUCKET; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Klim de toren voordat de lava je inhaalt — veilig of riskant."; }
    @Override protected String   objective()   { return "Bereik als eerste de top terwijl de lava stijgt."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "Plaatsingspunten — wie eerder boven is, scoort meer",
                "+30 ptn — de finish bereiken",
                "+10 ptn — per bonus-orb (op de riskante route)");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new InfernoArena(this);
        gameManager = new RisingInfernoGameManagerV2(this, reg, stats);
        return gameManager;
    }

    @Override
    protected List<nl.kmc.core.setup.SetupStep> extraSetupSteps(Player viewer) {
        if (arena == null) return List.of();
        var a = arena;
        List<nl.kmc.core.setup.SetupStep> s = new ArrayList<>();
        s.add(nl.kmc.core.setup.SetupStep.action("Lava-start", a.getLavaBaseY() != null ? "Y=" + a.getLavaBaseY() : "niet gezet",
                a.getLavaBaseY() != null, Material.LAVA_BUCKET,
                p -> { a.setLavaBase(p.getLocation()); p.sendMessage("§a[Setup] De lava begint op Y=" + a.getLavaBaseY() + "."); },
                "Klik: de lava begint op het blok waar je op staat"));
        s.add(nl.kmc.core.setup.SetupStep.action("Lava-gebied", a.getLavaArea() != null ? "✓ gezet" : "niet gezet",
                a.getLavaArea() != null, Material.MAGMA_BLOCK,
                p -> p.sendMessage("§e[Setup] Gebruik /inferno lava pos1 en /inferno lava pos2 op twee tegenover elkaar liggende hoeken."),
                "Klik: toont hoe je het lava-gebied instelt"));
        s.add(nl.kmc.core.setup.SetupStep.action("Finish", a.getFinish() != null ? "✓ gezet" : "niet gezet",
                a.getFinish() != null, Material.GOLD_BLOCK,
                p -> p.sendMessage("§e[Setup] Gebruik /inferno finish pos1 en /inferno finish pos2 (3 blokken hoog)."),
                "Klik: toont hoe je de finish instelt"));
        s.add(nl.kmc.core.setup.SetupStep.action("Startposities", a.getSpawns().size() + " (min. 1)",
                !a.getSpawns().isEmpty(), Material.QUARTZ_PILLAR,
                p -> { a.addSpawn(p.getLocation()); p.sendMessage("§a[Setup] Startpositie #" + a.getSpawns().size() + " toegevoegd."); },
                "Klik: voeg een startpositie toe op jouw locatie"));
        s.add(nl.kmc.core.setup.SetupStep.action("Bonus-orbs (optioneel)", a.getOrbs().size() + " orbs", true,
                Material.NETHER_STAR,
                p -> { a.addOrb(p.getLocation().add(0, 1, 0)); p.sendMessage("§a[Setup] Orb #" + a.getOrbs().size() + " toegevoegd."); },
                "Klik: zet een bonus-orb boven jouw hoofd (op de riskante route)"));
        return s;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new InfernoCommand(this);
        var bukkitCmd = getCommand("inferno");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new InfernoListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (gameManager != null && gameManager.isRunning()) gameManager.end();
    }

    @Override
    protected void onV1GameStart(String gameId) { /* V2 only */ }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * /kmctest: a 25x25 floor with a 30-step tower. The SAFE route is a spiral staircase along the wall (long,
     * easy); the RISKY route is a helix of single-block pillars through the middle (short, needs jumps) with
     * bonus orbs on it. Both end at the finish plate in the centre, 31 blocks up.
     */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        final int cx = a.getBlockX(), cz = a.getBlockZ(), y0 = a.getBlockY();
        final int steps = 30;

        TestArenaKit.fill(w, cx - 14, y0 - 1, cz - 14, cx + 14, y0 + steps + 8, cz + 14, Material.AIR);
        TestArenaKit.platform(w, cx, y0, cz, 12, 12, Material.STONE_BRICKS);

        // Safe route: the walkway runs round the wall, one block up every three cells, two blocks wide.
        List<int[]> ring = new ArrayList<>();
        int h = 11;
        for (int x = -h; x < h; x++) ring.add(new int[] {x, -h});
        for (int z = -h; z < h; z++) ring.add(new int[] {h, z});
        for (int x = h; x > -h; x--) ring.add(new int[] {x, h});
        for (int z = h; z > -h; z--) ring.add(new int[] {-h, z});
        for (int i = 0; i < steps; i++) {
            for (int k = 0; k < 3; k++) {
                int[] c = ring.get((i * 3 + k) % ring.size());
                int ix = Math.abs(c[0]) == h ? c[0] - Integer.signum(c[0]) : c[0];
                int iz = Math.abs(c[1]) == h ? c[1] - Integer.signum(c[1]) : c[1];
                w.getBlockAt(cx + c[0], y0 + 1 + i, cz + c[1]).setType(Material.LIGHT_BLUE_CONCRETE, false);
                w.getBlockAt(cx + ix, y0 + 1 + i, cz + iz).setType(Material.LIGHT_BLUE_CONCRETE, false);
            }
        }

        // Finish plate and the way to it from the end of the walkway.
        TestArenaKit.platform(w, cx, y0 + steps + 1, cz, 3, 3, Material.GOLD_BLOCK);
        int[] end = ring.get((steps * 3 - 1) % ring.size());        // last walkway cell, on the north wall
        int ex = end[0], ez = end[1];
        int dz = Integer.signum(-3 - ez), dx = Integer.signum(-4 - ex);
        for (int z = ez; z != -3 + dz; z += dz == 0 ? 1 : dz) {      // leg 1 towards the plate's z range
            w.getBlockAt(cx + ex, y0 + steps, cz + z).setType(Material.LIGHT_BLUE_CONCRETE, false);
            w.getBlockAt(cx + ex + 1, y0 + steps, cz + z).setType(Material.LIGHT_BLUE_CONCRETE, false);
            if (dz == 0) break;
        }
        for (int x = ex; x != -4 + dx; x += dx == 0 ? 1 : dx) {      // leg 2 up to the plate's edge
            w.getBlockAt(cx + x, y0 + steps, cz - 3).setType(Material.LIGHT_BLUE_CONCRETE, false);
            w.getBlockAt(cx + x, y0 + steps, cz - 2).setType(Material.LIGHT_BLUE_CONCRETE, false);
            if (dx == 0) break;
        }

        // Risky route: single-block pillars in a helix, one block higher each, with orbs on a few of them.
        List<Location> orbs = new ArrayList<>();
        for (int j = 0; j < steps; j++) {
            double ang = j * 0.56;
            double r = 7.5 - 0.1 * j;
            int px = cx + (int) Math.round(r * Math.cos(ang));
            int pz = cz + (int) Math.round(r * Math.sin(ang));
            w.getBlockAt(px, y0 + 1 + j, pz).setType(Material.MAGMA_BLOCK, false);
            if (j > 0 && j % 6 == 0) orbs.add(new Location(w, px + 0.5, y0 + 3 + j, pz + 0.5));
        }

        // Arena registration
        Box lava = Box.of(cx - 12, y0 - 1, cz - 12, cx + 12, y0 - 1, cz + 12);
        Box finish = Box.of(cx - 3, y0 + steps + 2, cz - 3, cx + 3, y0 + steps + 4, cz + 3);
        List<Location> spawns = TestArenaKit.ring(w, cx, y0 + 1, cz, 5, 12);
        Location spectator = new Location(w, cx + 0.5, y0 + steps + 7, cz + 0.5);
        arena.configure(w, lava, y0 - 1, finish, spawns, orbs, spectator, 75);
        return spawns.get(0);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public InfernoArena getArena() { return arena; }
    public RisingInfernoGameManagerV2 getGameManagerV2() { return gameManager; }
}
