package nl.kmc.crafting;

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
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class CraftingCountdownPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "crafting_countdown";

    private ArenaConfig arena;
    private CraftingCountdownManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Crafting Countdown"; }
    @Override protected Material icon() { return Material.CRAFTING_TABLE; }
    @Override protected int minPlayers() { return 1; }
    @Override protected String description() { return "Craft het doel-item en lever het in voor de tijd om is."; }
    @Override protected String objective() { return "Voltooi de meeste opdrachten als team."; }
    @Override protected List<String> scoringLines() {
        return List.of("+10 / +25 / +45 ptn — makkelijke / gemiddelde / moeilijke opdracht",
                "+10 ptn — eerste inlevering", "+30 ptn — alle opdrachten voltooid");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new CraftingCountdownManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new CraftCommand();
        var c = getCommand("craftcountdown");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld");
        int a = arena.locs("areas").size(), d = arena.locs("deliveries").size(), s = arena.locs("supplies").size();
        if (a == 0) p.add("geen werkgebieden");
        if (a != d || a != s) p.add("centers/inleverblokken/kisten aantallen kloppen niet");
        if (Recipe.parse(getConfig().getList("recipes"), new ArrayList<>()).isEmpty()) p.add("geen geldige recepten");
        return p;
    }

    private final class CraftCommand extends ArcadeCommand {
        CraftCommand() {
            super(CraftingCountdownPlugin.this, CraftingCountdownPlugin.this.arena,
                    () -> (ArcadeGameManager) CraftingCountdownPlugin.this.manager, "craftcountdown.admin", "Crafting Countdown");
        }

        @Override protected List<String> problems() { return CraftingCountdownPlugin.this.problems(); }
        @Override protected List<String> extraSubs() { return List.of("area", "reload"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            return sub.equals("area") && a.length == 2 ? List.of("center", "delivery", "supply", "clear", "list") : List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            if (sub.equals("reload")) { reloadConfig(); ok(s, "Config herladen."); return true; }
            if (!sub.equals("area")) return false;
            if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return true; }
            String act = a.length > 1 ? a[1].toLowerCase() : "list";
            switch (act) {
                case "center" -> ok(s, "Center #" + arena.addLoc("areas", p.getLocation()) + " toegevoegd.");
                case "delivery", "supply" -> {
                    Block b = p.getTargetBlockExact(6);
                    if (b == null) { err(s, "Kijk naar het blok (binnen 6 blokken)."); return true; }
                    if (act.equals("supply") && !(b.getState() instanceof org.bukkit.block.Container)) {
                        err(s, "Dat blok is geen kist/vat.");
                        return true;
                    }
                    Location l = b.getLocation().add(0.5, 0, 0.5);
                    int n = arena.addLoc(act.equals("delivery") ? "deliveries" : "supplies", l);
                    ok(s, (act.equals("delivery") ? "Inlevermoment #" : "Kist #") + n + " toegevoegd.");
                }
                case "clear" -> {
                    arena.clearLocs("areas");
                    arena.clearLocs("deliveries");
                    arena.clearLocs("supplies");
                    ok(s, "Werkgebieden gewist.");
                }
                default -> s.sendMessage("§7Centers: §f" + arena.locs("areas").size() + " §7| inlevermomenten: §f"
                        + arena.locs("deliveries").size() + " §7| kisten: §f" + arena.locs("supplies").size());
            }
            return true;
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** Eight work areas on a ring: crafting table, supply chest and a delivery barrel each. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 30, y - 1, cz - 30, cx + 30, y + 6, cz + 30, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 28, 28, Material.SMOOTH_STONE);

        arena.setWorld(w);
        arena.clearLocs("areas");
        arena.clearLocs("deliveries");
        arena.clearLocs("supplies");
        int count = 8;
        for (int i = 0; i < count; i++) {
            double ang = Math.PI * 2 * i / count;
            int ax = cx + (int) Math.round(Math.cos(ang) * 20), az = cz + (int) Math.round(Math.sin(ang) * 20);
            TestArenaKit.platform(w, ax, y, az, 3, 3, i % 2 == 0 ? Material.WHITE_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
            w.getBlockAt(ax + 2, y + 1, az).setType(Material.CRAFTING_TABLE, false);
            w.getBlockAt(ax - 2, y + 1, az).setType(Material.CHEST, false);
            w.getBlockAt(ax, y + 1, az + 3).setType(Material.BARREL, false);
            arena.addLoc("areas", TestArenaKit.stand(w, ax, y + 1, az, 0f));
            arena.addLoc("supplies", new Location(w, ax - 2 + 0.5, y + 1, az + 0.5));
            arena.addLoc("deliveries", new Location(w, ax + 0.5, y + 1, az + 3 + 0.5));
        }
        return arena.locs("areas").get(0);
    }
}
