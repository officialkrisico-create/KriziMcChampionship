package nl.kmc.laser;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.TestArenaKit;
import nl.kmc.game.api.arcade.ArcadeCommand;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Laser Logic: team puzzle boards where mirrors have to be turned until the beam reaches the receivers.
 * Setup: {@code /laser world}, {@code /laser board add} (stand on the corner with the lowest x and z of
 * every team's board, at the height of the components), then {@code /laser validate}.
 */
public final class LaserLogicPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "laser_logic";

    private ArenaConfig arena;
    private LaserLogicManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Laser Logic"; }
    @Override protected Material icon() { return Material.REDSTONE_LAMP; }
    @Override protected int minPlayers() { return 1; }
    @Override protected String description() { return "Draai de spiegels zo dat de laserstraal alle ontvangers bereikt."; }
    @Override protected String objective() { return "Los de meeste puzzels op als team."; }

    @Override
    protected List<String> scoringLines() {
        var c = getConfig();
        return List.of(
                "+" + c.getInt("points.easy", 100) + " / +" + c.getInt("points.medium", 175) + " / +"
                        + c.getInt("points.hard", 275) + " ptn — makkelijke / gemiddelde / moeilijke puzzel",
                "+" + c.getInt("points.first-completion-bonus", 25) + " ptn — als eerste team opgelost",
                "+" + c.getInt("points.no-hint-bonus", 15) + " ptn — zonder hint",
                placementLine() + " (teamplaats volgens KMC)");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new LaserLogicManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new LaserCommand();
        var c = getCommand("laser");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    private final class LaserCommand extends ArcadeCommand {
        LaserCommand() {
            super(LaserLogicPlugin.this, LaserLogicPlugin.this.arena,
                    () -> (ArcadeGameManager) LaserLogicPlugin.this.manager, "laserlogic.admin", "Laser Logic");
        }

        @Override protected List<String> problems() {
            return manager != null ? manager.getArenaIssues() : List.of("game niet beschikbaar");
        }

        @Override protected List<String> extraSubs() { return List.of("board", "puzzle", "info", "test", "reload"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            if (sub.equals("board") && a.length == 2) return List.of("add", "clear", "list");
            if (sub.equals("puzzle") && a.length == 2) return List.of("list", "show");
            if (sub.equals("puzzle") && a.length == 3) {
                List<String> ids = new ArrayList<>();
                for (PuzzleDef d : manager.loadPuzzles(new ArrayList<>())) ids.add(d.id());
                return ids;
            }
            return List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            switch (sub) {
                case "reload" -> { reloadConfig(); ok(s, "Config herladen."); return true; }
                case "board" -> { board(s, a); return true; }
                case "puzzle" -> { puzzle(s, a); return true; }
                case "info" -> { info(s); return true; }
                case "test" -> { test(s); return true; }
                default -> { return false; }
            }
        }

        private void info(CommandSender s) {
            List<String> problems = new ArrayList<>();
            List<PuzzleDef> defs = manager.loadPuzzles(problems);
            int e = 0, m = 0, h = 0;
            for (PuzzleDef d : defs) {
                switch (d.difficulty()) { case EASY -> e++; case MEDIUM -> m++; case HARD -> h++; }
            }
            s.sendMessage("§6Laser Logic §7— borden: §f" + arena.locs("boards").size() + " §7| puzzels: §f" + defs.size()
                    + " §7(makkelijk " + e + ", gemiddeld " + m + ", moeilijk " + h + ") §7| generator: §f"
                    + getConfig().getBoolean("generator.enabled", true));
        }

        /** Dry validation of every puzzle: solvable, not solved at the start, and what a generated one looks like. */
        private void test(CommandSender s) {
            List<String> problems = new ArrayList<>();
            List<PuzzleDef> defs = manager.loadPuzzles(problems);
            if (problems.isEmpty()) ok(s, defs.size() + " puzzels geldig en oplosbaar.");
            else problems.forEach(p -> err(s, p));
            PuzzleDef g = PuzzleGenerator.generate("test", PuzzleDef.Difficulty.MEDIUM, 11, 9, 4, 175, 120, new java.util.Random());
            s.sendMessage(g != null ? "§7Generator: §agelukt §7(een gegenereerde medium-puzzel is geldig)" : "§7Generator: §cmislukt");
        }

        private void board(CommandSender s, String[] a) {
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    Location at = p.getLocation().getBlock().getLocation();
                    int n = arena.addLoc("boards", at);
                    ok(s, "Bord #" + n + " (team " + n + " in alfabetische volgorde) staat op " + at.getBlockX() + ", "
                            + at.getBlockY() + ", " + at.getBlockZ() + " — dit is de hoek met de laagste x en z.");
                }
                case "clear" -> { arena.clearLocs("boards"); ok(s, "Borden gewist."); }
                default -> {
                    int i = 1;
                    for (Location l : arena.locs("boards"))
                        s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                    if (i == 1) s.sendMessage("§7Geen borden.");
                }
            }
        }

        private void puzzle(CommandSender s, String[] a) {
            List<String> problems = new ArrayList<>();
            List<PuzzleDef> defs = manager.loadPuzzles(problems);
            if (a.length < 3 || !a[1].equalsIgnoreCase("show")) {
                for (PuzzleDef d : defs)
                    s.sendMessage("§7" + d.id() + " §8— §f" + d.difficulty() + " §7" + d.points() + " ptn, "
                            + d.timeLimitSeconds() + " s, " + d.board().width() + "x" + d.board().height());
                return;
            }
            for (PuzzleDef d : defs) {
                if (!d.id().equalsIgnoreCase(a[2])) continue;
                s.sendMessage("§6" + d.id() + " §7— " + d.difficulty() + " (> emitter, / \\ spiegel, R ontvanger, # muur)");
                for (String row : d.board().toAscii()) s.sendMessage("§f" + row);
                var sol = LaserSim.solve(d.board(), PuzzleDef.MAX_STEPS);
                s.sendMessage(sol != null ? "§7Oplossing: §f" + sol : "§cGeen oplossing!");
                return;
            }
            err(s, "Onbekende puzzel '" + a[2] + "'.");
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** Eight boards of 11 x 9 in two rows of four, each on its own platform. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 40, y - 1, cz - 30, cx + 40, y + 8, cz + 30, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 38, 28, Material.SMOOTH_STONE);

        arena.setWorld(w);
        arena.clearLocs("boards");
        arena.clearLocs("spawns");
        for (int i = 0; i < 8; i++) {
            int col = i % 4, row = i / 4;
            int bx = cx - 36 + col * 19 + 4, bz = cz - 24 + row * 26;
            arena.addLoc("boards", new Location(w, bx, y + 1, bz));
        }
        return arena.locs("boards").get(0).clone().add(-2, 0, 4);
    }
}
