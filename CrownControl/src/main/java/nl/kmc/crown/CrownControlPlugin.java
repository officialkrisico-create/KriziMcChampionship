package nl.kmc.crown;

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

public final class CrownControlPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "crown_control";

    private ArenaConfig arena;
    private CrownControlManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Crown Control"; }
    @Override protected Material icon() { return Material.GOLDEN_HELMET; }
    @Override protected int minPlayers() { return 2; }
    @Override protected String description() { return "Vecht om de kroon en gebruik zijn energie voor teamvaardigheden."; }
    @Override protected String objective() { return "Bezit de kroon zo lang mogelijk."; }
    @Override protected List<String> scoringLines() {
        return List.of("+25 ptn — kroon veroveren", "+10 ptn — per 10 s bezit", "+15 ptn — overname",
                "+50 ptn — kroon bezitten bij het einde");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new CrownControlManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new CrownCommand();
        var c = getCommand("crown");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld");
        if (arena.region("zone") == null) p.add("geen kroon-zone");
        if (arena.locs("spawns").isEmpty()) p.add("geen spawns");
        return p;
    }

    private final class CrownCommand extends ArcadeCommand {
        CrownCommand() {
            super(CrownControlPlugin.this, CrownControlPlugin.this.arena,
                    () -> (ArcadeGameManager) CrownControlPlugin.this.manager, "crown.admin", "Crown Control");
        }

        @Override protected List<String> problems() { return CrownControlPlugin.this.problems(); }
        @Override protected List<String> extraSubs() { return List.of("zone", "lockdown", "use"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            if (sub.equals("lockdown") && a.length == 2) return List.of("add", "remove", "list");
            return List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return true; }
            switch (sub) {
                case "use" -> {
                    if (manager != null && manager.isRunning()) manager.openMenu(p);
                    else err(s, "Er loopt geen game.");
                }
                case "zone" -> {
                    Location[] sel = arena.selection(p);
                    if (sel == null) { err(s, "Zet eerst /" + label + " pos1 en pos2."); return true; }
                    arena.setRegion("zone", sel[0], sel[1]);
                    ok(s, "Kroon-zone gezet.");
                }
                case "lockdown" -> {
                    String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
                    switch (act) {
                        case "add" -> {
                            Location[] sel = arena.selection(p);
                            if (sel == null || a.length < 3) { err(s, "Gebruik pos1 + pos2 en /" + label + " lockdown add <id>"); return true; }
                            arena.setRegion("lockdown." + a[2], sel[0], sel[1]);
                            ok(s, "Lockdown-zone " + a[2] + " gezet.");
                        }
                        case "remove" -> {
                            if (a.length < 3) { err(s, "Gebruik: /" + label + " lockdown remove <id>"); return true; }
                            arena.remove("lockdown." + a[2]);
                            ok(s, "Lockdown-zone " + a[2] + " verwijderd.");
                        }
                        default -> s.sendMessage("§7Lockdown-zones: §f" + String.join(", ", arena.regions("lockdown").keySet()));
                    }
                }
                default -> { return false; }
            }
            return true;
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** A 60x60 floor, a golden crown pad in the middle, two lockdown zones in front of it, 8 spawns on a ring. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 32, y - 1, cz - 32, cx + 32, y + 8, cz + 32, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 30, 30, Material.POLISHED_ANDESITE);
        TestArenaKit.disc(w, cx, y, cz, 5, Material.GOLD_BLOCK);
        TestArenaKit.platform(w, cx - 12, y, cz, 3, 3, Material.IRON_BLOCK);
        TestArenaKit.platform(w, cx + 12, y, cz, 3, 3, Material.IRON_BLOCK);

        arena.setWorld(w);
        arena.setRegion("zone", new Location(w, cx - 5, y + 1, cz - 5), new Location(w, cx + 5, y + 4, cz + 5));
        for (String id : new ArrayList<>(arena.regions("lockdown").keySet())) arena.remove("lockdown." + id);
        arena.setRegion("lockdown.west", new Location(w, cx - 15, y + 1, cz - 3), new Location(w, cx - 9, y + 4, cz + 3));
        arena.setRegion("lockdown.east", new Location(w, cx + 9, y + 1, cz - 3), new Location(w, cx + 15, y + 4, cz + 3));
        arena.clearLocs("spawns");
        for (Location s : TestArenaKit.ring(w, cx, y + 1, cz, 25, 8)) arena.addLoc("spawns", s);
        return arena.locs("spawns").get(0);
    }
}
