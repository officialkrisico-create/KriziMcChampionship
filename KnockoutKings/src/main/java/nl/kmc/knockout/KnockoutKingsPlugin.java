package nl.kmc.knockout;

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

public final class KnockoutKingsPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "knockout_kings";

    private ArenaConfig arena;
    private KnockoutKingsManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Knockout Kings"; }
    @Override protected Material icon() { return Material.STICK; }
    @Override protected int minPlayers() { return 2; }
    @Override protected String description() { return "Sla je tegenstanders van het platform — drie rondes."; }
    @Override protected String objective() { return "Behaal de meeste punten met eliminaties en overleven."; }
    @Override protected List<String> scoringLines() {
        return List.of("+15 ptn — eliminatie", "+5 ptn — assist", "+20 ptn — overleven tot de laatste 30 s",
                "+40 ptn — rondewinnaar", "+15 ptn — perfecte ronde");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new KnockoutKingsManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new KnockoutCommand();
        var c = getCommand("knockout");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld");
        if (arena.region("platform") == null) p.add("geen platform");
        if (arena.locs("spawns").isEmpty()) p.add("geen startposities");
        return p;
    }

    private final class KnockoutCommand extends ArcadeCommand {
        KnockoutCommand() {
            super(KnockoutKingsPlugin.this, KnockoutKingsPlugin.this.arena, () -> (ArcadeGameManager) KnockoutKingsPlugin.this.manager, "knockout.admin", "Knockout Kings");
        }

        @Override protected List<String> problems() { return KnockoutKingsPlugin.this.problems(); }
        @Override protected List<String> extraSubs() { return List.of("platform", "respawn", "killy"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            return sub.equals("respawn") && a.length == 2 ? List.of("add", "clear") : List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return true; }
            switch (sub) {
                case "platform" -> {
                    Location[] sel = arena.selection(p);
                    if (sel == null) { err(s, "Zet eerst /" + label + " pos1 en pos2."); return true; }
                    arena.setRegion("platform", sel[0], sel[1]);
                    ok(s, "Platform gezet.");
                }
                case "respawn" -> {
                    if (a.length > 1 && a[1].equalsIgnoreCase("clear")) { arena.clearLocs("respawns"); ok(s, "Respawns gewist."); }
                    else ok(s, "Respawn #" + arena.addLoc("respawns", p.getLocation()) + " toegevoegd.");
                }
                case "killy" -> {
                    getConfig().set("arena.kill-y", p.getLocation().getBlockY());
                    saveConfig();
                    ok(s, "Spelers onder Y=" + p.getLocation().getBlockY() + " zijn eruit.");
                }
                default -> { return false; }
            }
            return true;
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** A 29x29 stone-brick platform in the sky with 16 start positions on a ring. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 16, y - 1, cz - 16, cx + 16, y + 6, cz + 16, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 14, 14, Material.STONE_BRICKS);
        TestArenaKit.platform(w, cx, y, cz, 3, 3, Material.GOLD_BLOCK);

        arena.setWorld(w);
        arena.setRegion("platform", new Location(w, cx - 14, y, cz - 14), new Location(w, cx + 14, y, cz + 14));
        arena.clearLocs("spawns");
        for (Location s : TestArenaKit.ring(w, cx, y + 1, cz, 10, 16)) arena.addLoc("spawns", s);
        arena.clearLocs("respawns");
        for (Location s : TestArenaKit.ring(w, cx, y + 1, cz, 5, 8)) arena.addLoc("respawns", s);
        getConfig().set("arena.kill-y", y - 8);
        saveConfig();
        return arena.locs("spawns").get(0);
    }
}
