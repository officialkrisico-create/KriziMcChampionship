package nl.kmc.blockball;

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
 * Blockball: team football with a logical ball. Setup per field:
 * {@code /blockball pos1}+{@code pos2} then {@code field add <id>}, the same for {@code goal <id> a|b},
 * then {@code ball <id>} and {@code fspawn <id> a|b}; {@code spawn add} sets the waiting spot.
 */
public final class BlockballPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "blockball";

    private ArenaConfig arena;
    private BlockballManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Blockball"; }
    @Override protected Material icon() { return Material.SLIME_BLOCK; }
    @Override protected int minPlayers() { return 2; }
    @Override protected String description() { return "Voetbal met een blokbal: schop de bal in het doel van de tegenstander."; }
    @Override protected String objective() { return "Win je wedstrijd door meer doelpunten te maken."; }

    @Override
    protected List<String> scoringLines() {
        var c = getConfig();
        return List.of(
                "+" + c.getInt("points.win", 300) + " / +" + c.getInt("points.draw", 225) + " / +"
                        + c.getInt("points.loss", 150) + " ptn — wedstrijd gewonnen / gelijk / verloren",
                "+" + c.getInt("points.goal", 10) + " ptn — doelpunt, +" + c.getInt("points.assist", 5) + " ptn — assist",
                "Linksklik = schop, sneak + linksklik = schot, rechtsklik = pass");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new BlockballManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new BallCommand();
        var c = getCommand("blockball");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    private final class BallCommand extends ArcadeCommand {
        BallCommand() {
            super(BlockballPlugin.this, BlockballPlugin.this.arena,
                    () -> (ArcadeGameManager) BlockballPlugin.this.manager, "blockball.admin", "Blockball");
        }

        @Override protected List<String> problems() {
            return manager != null ? manager.getArenaIssues() : List.of("game niet beschikbaar");
        }

        @Override protected List<String> extraSubs() { return List.of("field", "goal", "ball", "fspawn", "info", "reload"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            if (sub.equals("field") && a.length == 2) return List.of("add", "remove", "list", "clear");
            if ((sub.equals("goal") || sub.equals("fspawn")) && a.length == 3) return List.of("a", "b");
            if ((sub.equals("goal") || sub.equals("fspawn") || sub.equals("ball")) && a.length == 2)
                return new ArrayList<>(arena.regions("fields").keySet());
            return List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            switch (sub) {
                case "reload" -> { reloadConfig(); ok(s, "Config herladen."); return true; }
                case "info" -> { info(s); return true; }
                case "field" -> { field(s, a); return true; }
                case "goal" -> { goal(s, a); return true; }
                case "ball" -> { ball(s, a); return true; }
                case "fspawn" -> { fspawn(s, a); return true; }
                default -> { return false; }
            }
        }

        private void info(CommandSender s) {
            var fields = manager.loadFields(null);
            s.sendMessage("§6Blockball §7— velden (compleet): §f" + fields.size() + "§7/§f" + arena.regions("fields").size()
                    + " §7| wachtplekken: §f" + arena.locs("spawns").size());
            for (var f : fields)
                s.sendMessage("§7" + f.id() + " §8— §f" + (int) f.bounds().sizeX() + "x" + (int) f.bounds().sizeZ()
                        + " §7spawns A/B: " + f.spawnsA().size() + "/" + f.spawnsB().size());
        }

        private void field(CommandSender s, String[] a) {
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    if (a.length < 3) { err(s, "Gebruik: /blockball field add <id> (na pos1 en pos2)"); return; }
                    Location[] sel = arena.selection(p);
                    if (sel == null) { err(s, "Zet eerst pos1 en pos2 (de hoeken van het veld, vloer tot plafond)."); return; }
                    arena.setRegion("fields." + a[2].toLowerCase(Locale.ROOT), sel[0], sel[1]);
                    ok(s, "Veld '" + a[2] + "' opgeslagen. Volgende: goal " + a[2] + " a|b, ball " + a[2] + ", fspawn " + a[2] + " a|b.");
                }
                case "remove" -> {
                    if (a.length < 3) { err(s, "Gebruik: /blockball field remove <id>"); return; }
                    String id = a[2].toLowerCase(Locale.ROOT);
                    arena.remove("fields." + id);
                    arena.remove("goal_a." + id);
                    arena.remove("goal_b." + id);
                    arena.remove("ball." + id);
                    arena.remove("spawns_a." + id);
                    arena.remove("spawns_b." + id);
                    ok(s, "Veld '" + id + "' verwijderd.");
                }
                case "clear" -> {
                    for (String k : List.of("fields", "goal_a", "goal_b", "ball", "spawns_a", "spawns_b")) arena.remove(k);
                    ok(s, "Alle velden gewist.");
                }
                default -> {
                    for (String id : arena.regions("fields").keySet()) s.sendMessage("§7- §f" + id);
                    if (arena.regions("fields").isEmpty()) s.sendMessage("§7Geen velden.");
                }
            }
        }

        private void goal(CommandSender s, String[] a) {
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
            if (a.length < 3) { err(s, "Gebruik: /blockball goal <veld> a|b (na pos1 en pos2 om het doel)"); return; }
            Location[] sel = arena.selection(p);
            if (sel == null) { err(s, "Zet eerst pos1 en pos2 om het doel."); return; }
            String side = a[2].equalsIgnoreCase("b") ? "goal_b" : "goal_a";
            arena.setRegion(side + "." + a[1].toLowerCase(Locale.ROOT), sel[0], sel[1]);
            ok(s, "Doel " + a[2].toUpperCase(Locale.ROOT) + " van veld '" + a[1] + "' opgeslagen.");
        }

        private void ball(CommandSender s, String[] a) {
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
            if (a.length < 2) { err(s, "Gebruik: /blockball ball <veld> (op de middenstip)"); return; }
            arena.setLoc("ball." + a[1].toLowerCase(Locale.ROOT), p.getLocation());
            ok(s, "Bal-spawn van veld '" + a[1] + "' gezet.");
        }

        private void fspawn(CommandSender s, String[] a) {
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
            if (a.length < 3) { err(s, "Gebruik: /blockball fspawn <veld> a|b"); return; }
            String path = (a[2].equalsIgnoreCase("b") ? "spawns_b." : "spawns_a.") + a[1].toLowerCase(Locale.ROOT);
            ok(s, "Spawn #" + arena.addLoc(path, p.getLocation()) + " voor team " + a[2].toUpperCase(Locale.ROOT)
                    + " van veld '" + a[1] + "' toegevoegd.");
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** Four fields side by side, so eight teams play as four simultaneous matches. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 24, y - 1, cz - 60, cx + 24, y + 10, cz + 60, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 22, 58, Material.SMOOTH_STONE);

        for (String k : List.of("fields", "goal_a", "goal_b", "ball", "spawns_a", "spawns_b")) arena.remove(k);
        arena.setWorld(w);
        arena.clearLocs("spawns");

        int halfX = 14, halfZ = 8;                       // a field of 28 x 16 blocks
        for (int i = 0; i < 4; i++) {
            int fz = cz - 42 + i * 28;
            String id = "f" + (i + 1);
            // floor and walls
            TestArenaKit.platform(w, cx, y, fz, halfX, halfZ, i % 2 == 0 ? Material.GREEN_CONCRETE : Material.LIME_CONCRETE);
            TestArenaKit.walls(w, cx, y + 1, fz, halfX + 1, halfZ + 1, 4, Material.GLASS);
            Location lo = new Location(w, cx - halfX, y + 1, fz - halfZ + 0), hi = new Location(w, cx + halfX - 1, y + 6, fz + halfZ - 1);
            arena.setRegion("fields." + id, lo, hi);
            // goals: 3 deep, 8 wide, 3 high, painted in the team colours
            TestArenaKit.platform(w, cx - halfX + 1, y, fz, 1, 4, Material.RED_CONCRETE);
            TestArenaKit.platform(w, cx + halfX - 2, y, fz, 1, 4, Material.BLUE_CONCRETE);
            arena.setRegion("goal_a." + id, new Location(w, cx - halfX, y + 1, fz - 4), new Location(w, cx - halfX + 2, y + 3, fz + 3));
            arena.setRegion("goal_b." + id, new Location(w, cx + halfX - 3, y + 1, fz - 4), new Location(w, cx + halfX - 1, y + 3, fz + 3));
            arena.setLoc("ball." + id, new Location(w, cx + 0.5, y + 1.5, fz + 0.5));
            for (int k = 0; k < 4; k++) {
                arena.addLoc("spawns_a." + id, TestArenaKit.stand(w, cx - 8, y + 1, fz - 3 + k * 2, -90f));
                arena.addLoc("spawns_b." + id, TestArenaKit.stand(w, cx + 8, y + 1, fz - 3 + k * 2, 90f));
            }
        }
        // the waiting spot for teams that are not playing
        TestArenaKit.platform(w, cx, y, cz + 50, 8, 6, Material.QUARTZ_BLOCK);
        arena.addLoc("spawns", TestArenaKit.stand(w, cx, y + 1, cz + 50, 180f));
        return arena.locs("spawns").get(0);
    }
}
