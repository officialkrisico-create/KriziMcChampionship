package nl.kmc.corecharge;

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

public final class CoreChargePlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "core_charge";

    private ArenaConfig arena;
    private CoreChargeManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Core Charge"; }
    @Override protected Material icon() { return Material.ENDER_EYE; }
    @Override protected int minPlayers() { return 2; }
    @Override protected String description() { return "Laad de kern van je team op en kies wanneer je de energie inzet."; }
    @Override protected String objective() { return "Verzamel energie en gebruik die slim voor de Final Charge."; }
    @Override protected List<String> scoringLines() {
        return List.of("+5 ptn — per 10 verzamelde energie", "+15 ptn — energie-objective",
                "+30 ptn — kern volledig opladen", "+1 ptn — per 5 resterende energie", "+25 ptn — Final Charge");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new CoreChargeManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new CoreCommand();
        var c = getCommand("corecharge");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld");
        if (arena.locs("cores").isEmpty()) p.add("geen kernen");
        if (arena.locs("orbs").isEmpty() && arena.locs("stations").isEmpty()) p.add("geen energiebronnen");
        return p;
    }

    private final class CoreCommand extends ArcadeCommand {
        CoreCommand() {
            super(CoreChargePlugin.this, CoreChargePlugin.this.arena,
                    () -> (ArcadeGameManager) CoreChargePlugin.this.manager, "corecharge.admin", "Core Charge");
        }

        @Override protected List<String> problems() { return CoreChargePlugin.this.problems(); }
        @Override protected List<String> extraSubs() { return List.of("core", "orb", "station", "use"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            return List.of("core", "orb", "station").contains(sub) && a.length == 2 ? List.of("add", "clear", "list") : List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return true; }
            if (sub.equals("use")) {
                if (manager != null && manager.isRunning()) manager.openMenu(p);
                else err(s, "Er loopt geen game.");
                return true;
            }
            if (!List.of("core", "orb", "station").contains(sub)) return false;
            String path = sub + "s";
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "add";
            switch (act) {
                case "clear" -> { arena.clearLocs(path); ok(s, sub + "s gewist."); }
                case "list" -> {
                    int i = 1;
                    for (Location l : arena.locs(path))
                        s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                }
                default -> ok(s, sub + " #" + arena.addLoc(path, p.getLocation()) + " toegevoegd.");
            }
            return true;
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** 8 team cores on a ring, 12 orbs in between, 4 charge stations near the middle. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 32, y - 1, cz - 32, cx + 32, y + 8, cz + 32, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 30, 30, Material.DEEPSLATE_TILES);

        arena.setWorld(w);
        arena.clearLocs("cores");
        arena.clearLocs("orbs");
        arena.clearLocs("stations");
        arena.clearLocs("spawns");
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI * 2 * i / 8;
            int kx = cx + (int) Math.round(Math.cos(ang) * 24), kz = cz + (int) Math.round(Math.sin(ang) * 24);
            TestArenaKit.platform(w, kx, y, kz, 2, 2, Material.AMETHYST_BLOCK);
            w.getBlockAt(kx, y + 1, kz).setType(Material.CRYING_OBSIDIAN, false);
            arena.addLoc("cores", new Location(w, kx + 0.5, y + 2, kz + 0.5));
            int sx = cx + (int) Math.round(Math.cos(ang) * 20), sz = cz + (int) Math.round(Math.sin(ang) * 20);
            arena.addLoc("spawns", TestArenaKit.stand(w, sx, y + 1, sz, 0f));
        }
        for (int i = 0; i < 12; i++) {
            double ang = Math.PI * 2 * (i + 0.5) / 12;
            double r = i % 2 == 0 ? 12 : 17;
            arena.addLoc("orbs", new Location(w, cx + 0.5 + Math.cos(ang) * r, y + 1, cz + 0.5 + Math.sin(ang) * r));
        }
        int[][] st = {{0, -7}, {7, 0}, {0, 7}, {-7, 0}};
        for (int[] s : st) {
            TestArenaKit.disc(w, cx + s[0], y, cz + s[1], 3, Material.SEA_LANTERN);
            arena.addLoc("stations", new Location(w, cx + s[0] + 0.5, y + 1, cz + s[1] + 0.5));
        }
        return arena.locs("spawns").get(0);
    }
}
