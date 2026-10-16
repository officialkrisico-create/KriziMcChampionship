package nl.kmc.dungeon;

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

import java.util.List;
import java.util.Locale;

/**
 * Dungeon Draft: every team runs its own dungeon lane and drafts its route room by room. Setup:
 * {@code /dungeon world}, then stand on the origin block of every team's lane and run
 * {@code /dungeon lane add}. The rooms lie relative to that origin (config.yml {@code rooms[].at}).
 */
public final class DungeonDraftPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "dungeon_draft";

    private ArenaConfig arena;
    private DungeonDraftManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Dungeon Draft"; }
    @Override protected Material icon() { return Material.WITHER_SKELETON_SKULL; }
    @Override protected int minPlayers() { return 1; }
    @Override protected String description() { return "Kies kamer voor kamer je eigen route door een dungeon en versla de eindbaas."; }
    @Override protected String objective() { return "Kom met je team zo ver mogelijk door de dungeon."; }

    @Override
    protected List<String> scoringLines() {
        return List.of(
                "Beloning per voltooide kamer (gekozen route, zie het draft-menu)",
                "Bonus voor snelle kamers, de eindbaas en een volledig voltooide run",
                "Meer teamgezondheid over aan het eind geeft extra punten",
                placementLine() + " (teamplaats volgens KMC)");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new DungeonDraftManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new DungeonCommand();
        var c = getCommand("dungeon");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    private final class DungeonCommand extends ArcadeCommand {
        DungeonCommand() {
            super(DungeonDraftPlugin.this, DungeonDraftPlugin.this.arena,
                    () -> (ArcadeGameManager) DungeonDraftPlugin.this.manager, "dungeondraft.admin", "Dungeon Draft");
        }

        @Override protected List<String> problems() {
            return manager != null ? manager.getArenaIssues() : List.of("game niet beschikbaar");
        }

        @Override protected List<String> extraSubs() { return List.of("lane", "room", "info", "test", "reload"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            if (sub.equals("lane") && a.length == 2) return List.of("add", "clear", "list");
            if (sub.equals("room") && a.length == 2) return List.of("list", "info");
            return List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            switch (sub) {
                case "reload" -> { reloadConfig(); ok(s, "Config herladen."); return true; }
                case "lane" -> { lane(s, a); return true; }
                case "room" -> { room(s, a); return true; }
                case "info", "test" -> { info(s); return true; }
                default -> { return false; }
            }
        }

        private void info(CommandSender s) {
            var parsed = manager.loadRooms();
            s.sendMessage("§5Dungeon Draft §7— banen: §f" + arena.locs("lanes").size() + " §7| kamers: §f" + parsed.rooms.size());
            var problems = new java.util.ArrayList<>(parsed.problems);
            problems.addAll(DungeonLogic.validate(parsed.rooms, getConfig().getInt("dungeon.length", 5),
                    getConfig().getInt("dungeon.route-choices", 3), getConfig().getBoolean("dungeon.boss-last", true)));
            if (problems.isEmpty()) ok(s, "De kamers zijn geldig en genoeg voor een run.");
            else problems.forEach(p -> err(s, p));
        }

        private void room(CommandSender s, String[] a) {
            var rooms = manager.loadRooms().rooms;
            if (a.length > 2) {
                for (var r : rooms) {
                    if (!r.id().equalsIgnoreCase(a[2])) continue;
                    s.sendMessage("§e" + r.name() + " §7(" + r.id() + ") §8— §f" + r.type() + " §7moeilijkheid §f" + r.difficulty()
                            + " §7beloning §f" + r.reward() + " §7tijdslimiet §f" + r.timeLimitSeconds() + "s");
                    return;
                }
                err(s, "Onbekende kamer " + a[2]);
                return;
            }
            for (var r : rooms)
                s.sendMessage("§7- §f" + r.id() + " §8| §e" + r.type() + " §8| §7★" + r.difficulty() + " §8| §7" + r.reward() + " ptn");
            if (rooms.isEmpty()) s.sendMessage("§7Geen kamers.");
        }

        private void lane(CommandSender s, String[] a) {
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    Location at = p.getLocation().getBlock().getLocation();
                    int n = arena.addLoc("lanes", at);
                    ok(s, "Baan #" + n + " (team " + n + " in alfabetische volgorde) heeft zijn oorsprong op " + at.getBlockX()
                            + ", " + at.getBlockY() + ", " + at.getBlockZ() + ".");
                }
                case "clear" -> { arena.clearLocs("lanes"); ok(s, "Banen gewist."); }
                default -> {
                    int i = 1;
                    for (Location l : arena.locs("lanes"))
                        s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                    if (i == 1) s.sendMessage("§7Geen banen.");
                }
            }
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * Eight lanes side by side. The floor under every room is built from the room's {@code at} offset, so
     * a fresh server can play a full run (the parkour rooms get stepping stones with gaps).
     */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        var rooms = manager.loadRooms().rooms;
        int maxX = 14;
        for (var r : rooms) if (r.at() != null) maxX = Math.max(maxX, r.at()[0] + 14);
        int lanes = 8, spacing = 12;
        int x0 = cx - maxX / 2;
        int zTop = cz - lanes * spacing / 2;
        TestArenaKit.fill(w, x0 - 3, y - 3, zTop - 8, x0 + maxX + 4, y + 14, zTop + lanes * spacing + 4, Material.AIR);
        arena.setWorld(w);
        arena.clearLocs("lanes");
        arena.clearLocs("spawns");
        for (int i = 0; i < lanes; i++) {
            int lz = zTop + i * spacing + spacing / 2;
            Material floor = i % 2 == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_DEEPSLATE;
            // the lobby, where the team waits and drafts
            TestArenaKit.fill(w, x0, y, lz - 4, x0 + 12, y, lz + 4, Material.POLISHED_ANDESITE);
            for (var r : rooms) {
                if (r.at() == null) continue;
                int rx = x0 + r.at()[0], rz = lz + r.at()[2];
                if (r.type() == DungeonLogic.RoomType.PARKOUR) {
                    // stepping stones with gaps: start pad, two checkpoint islands and the finish
                    TestArenaKit.fill(w, rx, y, rz - 2, rx + 2, y, rz + 2, floor);
                    TestArenaKit.fill(w, rx + 3, y, rz - 2, rx + 5, y, rz + 2, Material.SMOOTH_STONE);
                    TestArenaKit.fill(w, rx + 7, y, rz - 2, rx + 9, y, rz + 2, Material.SMOOTH_STONE);
                    TestArenaKit.fill(w, rx + 11, y, rz - 2, rx + 12, y, rz + 2, Material.GOLD_BLOCK);
                } else {
                    TestArenaKit.fill(w, rx, y, rz - 4, rx + 12, y, rz + 4, floor);
                    if (r.type() == DungeonLogic.RoomType.BOSS) {
                        TestArenaKit.fill(w, rx, y + 1, rz - 4, rx, y + 3, rz + 4, Material.OBSIDIAN);
                    }
                }
            }
            arena.addLoc("lanes", new Location(w, x0, y + 1, lz));
        }
        return arena.locs("lanes").get(0).clone().add(6.5, 0, 0.5);
    }
}
