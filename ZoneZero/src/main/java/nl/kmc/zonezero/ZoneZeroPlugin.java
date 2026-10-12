package nl.kmc.zonezero;

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

public final class ZoneZeroPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "zone_zero";

    private ArenaConfig arena;
    private ZoneZeroManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Zone Zero"; }
    @Override protected Material icon() { return Material.BEACON; }
    @Override protected int minPlayers() { return 2; }
    @Override protected String description() { return "Verover en behoud de zones die open zijn — ze wisselen steeds."; }
    @Override protected String objective() { return "Controleer de actieve zones langer dan de andere teams."; }
    @Override protected List<String> scoringLines() {
        return List.of("+20 ptn — zone veroveren", "+10 ptn — zone 10 s behouden", "+10 ptn — overname",
                "+30 ptn — alle open zones tegelijk");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new ZoneZeroManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new ZoneCommand();
        var c = getCommand("zonezero");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld");
        int n = arena.regions("zones").size();
        int need = Math.max(1, getConfig().getInt("game.active-zones", 2));
        if (n <= need) p.add("minstens " + (need + 1) + " zones nodig (nu " + n + ")");
        if (arena.locs("spawns").isEmpty()) p.add("geen spawns");
        return p;
    }

    private final class ZoneCommand extends ArcadeCommand {
        ZoneCommand() {
            super(ZoneZeroPlugin.this, ZoneZeroPlugin.this.arena,
                    () -> (ArcadeGameManager) ZoneZeroPlugin.this.manager, "zonezero.admin", "Zone Zero");
        }

        @Override protected List<String> problems() { return ZoneZeroPlugin.this.problems(); }
        @Override protected List<String> extraSubs() { return List.of("zone"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            if (!sub.equals("zone")) return List.of();
            if (a.length == 2) return List.of("add", "remove", "list");
            if (a.length == 3 && a[1].equalsIgnoreCase("remove")) return new ArrayList<>(arena.regions("zones").keySet());
            return List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            if (!sub.equals("zone")) return false;
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return true; }
                    if (a.length < 3) { err(s, "Gebruik: /" + label + " zone add <id>"); return true; }
                    Location[] sel = arena.selection(p);
                    if (sel == null) { err(s, "Zet eerst /" + label + " pos1 en pos2 (hoogte erbij nemen)."); return true; }
                    arena.setRegion("zones." + a[2], sel[0], sel[1]);
                    ok(s, "Zone " + a[2] + " gezet.");
                }
                case "remove" -> {
                    if (a.length < 3) { err(s, "Gebruik: /" + label + " zone remove <id>"); return true; }
                    arena.remove("zones." + a[2]);
                    ok(s, "Zone " + a[2] + " verwijderd.");
                }
                default -> {
                    var zs = arena.regions("zones");
                    if (zs.isEmpty()) s.sendMessage("§7Geen zones.");
                    zs.forEach((id, r) -> s.sendMessage("§7- §f" + id + " §8(" + (int) r.sizeX() + "x" + (int) r.sizeZ() + ")"));
                }
            }
            return true;
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** A 70x70 stone floor with a central pad and four corner pads as zones, and 8 team spawns on a ring. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 36, y - 1, cz - 36, cx + 36, y + 8, cz + 36, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 35, 35, Material.STONE);

        arena.setWorld(w);
        String[][] spec = {{"A", "0", "0", "Centrale toren"}, {"B", "0", "-24", "Noordbrug"},
                {"C", "24", "0", "Oostruine"}, {"D", "0", "24", "Zuidplein"}, {"E", "-24", "0", "Westgrot"}};
        Material[] pads = {Material.GOLD_BLOCK, Material.BLUE_CONCRETE, Material.RED_CONCRETE,
                Material.LIME_CONCRETE, Material.PURPLE_CONCRETE};
        for (var id : new ArrayList<>(arena.regions("zones").keySet())) arena.remove("zones." + id);
        for (int i = 0; i < spec.length; i++) {
            int zx = cx + Integer.parseInt(spec[i][1]), zz = cz + Integer.parseInt(spec[i][2]);
            TestArenaKit.platform(w, zx, y, zz, 5, 5, pads[i]);
            arena.setRegion("zones." + spec[i][0], new Location(w, zx - 5, y + 1, zz - 5), new Location(w, zx + 5, y + 4, zz + 5));
            getConfig().set("zone-props." + spec[i][0] + ".name", spec[i][3]);
        }
        getConfig().set("zone-props.A.score-multiplier", 2.0);
        getConfig().set("zone-props.C.speed", 2);
        arena.clearLocs("spawns");
        for (Location s : TestArenaKit.ring(w, cx, y + 1, cz, 31, 8)) arena.addLoc("spawns", s);
        saveConfig();
        return arena.locs("spawns").get(0);
    }
}
