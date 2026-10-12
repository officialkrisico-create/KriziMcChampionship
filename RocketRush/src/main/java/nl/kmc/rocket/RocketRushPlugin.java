package nl.kmc.rocket;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.TestArenaKit;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class RocketRushPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "rocket_rush";

    private ArenaConfig arena;
    private RocketRushManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Rocket Rush"; }
    @Override protected Material icon() { return Material.FIREWORK_ROCKET; }
    @Override protected int minPlayers() { return 1; }
    @Override protected String description() { return "Race met een elytra en vuurwerk door een luchtparcours."; }
    @Override protected String objective() { return "Vlieg de checkpoints in volgorde en finish als eerste."; }
    @Override protected List<String> scoringLines() {
        return List.of("+10 ptn — checkpoint", "+15 ptn — bonusring",
                "+100 / +85 / +70 ptn — 1e, 2e, 3e finish", "+25 ptn — finishbonus");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new RocketRushManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new RocketCommand(this, arena);
        var c = getCommand("rocket");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    ArcadeGameManager manager() { return manager; }

    List<String> problems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld");
        if (arena.locs("spawns").isEmpty()) p.add("geen startposities");
        if (arena.locs("checkpoints").isEmpty()) p.add("geen checkpoints");
        if (arena.loc("finish") == null) p.add("geen finish");
        return p;
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * A launch platform and a gentle glide of 5 checkpoints, 3 rings and 2 rocket pickups towards the
     * finish, 290 blocks away. Small markers (glowstone) show where the checkpoints are.
     */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y0 = a.getBlockY() + 20;

        TestArenaKit.fill(w, cx - 8, y0 - 1, cz - 8, cx + 8, y0 + 4, cz + 8, Material.AIR);
        TestArenaKit.platform(w, cx, y0 - 1, cz, 7, 7, Material.STONE_BRICKS);

        arena.setWorld(w);
        arena.clearLocs("spawns");
        for (Location s : TestArenaKit.ring(w, cx, y0, cz, 5, 12)) {
            s.setYaw(-90);   // facing +x
            arena.addLoc("spawns", s);
        }
        arena.clearLocs("checkpoints");
        arena.clearLocs("rings");
        arena.clearLocs("pickups");
        for (int i = 1; i <= 5; i++) {
            Location cp = new Location(w, cx + i * 45, y0 + 10 - i * 8, cz + (i % 2 == 0 ? 12 : -12));
            arena.addLoc("checkpoints", cp);
            w.getBlockAt(cp.getBlockX(), cp.getBlockY(), cp.getBlockZ()).setType(Material.GLOWSTONE, false);
        }
        for (int i = 0; i < 3; i++) {
            Location r = new Location(w, cx + 70 + i * 55, y0 - 5 - i * 9, cz + (i % 2 == 0 ? -25 : 25));
            arena.addLoc("rings", r);
            w.getBlockAt(r.getBlockX(), r.getBlockY(), r.getBlockZ()).setType(Material.SEA_LANTERN, false);
        }
        for (int i = 0; i < 2; i++) {
            Location pk = new Location(w, cx + 100 + i * 80, y0 - 20 - i * 10, cz);
            arena.addLoc("pickups", pk);
            w.getBlockAt(pk.getBlockX(), pk.getBlockY(), pk.getBlockZ()).setType(Material.EMERALD_BLOCK, false);
        }
        Location fin = new Location(w, cx + 290, y0 - 60, cz);
        arena.setLoc("finish", fin);
        TestArenaKit.platform(w, fin.getBlockX(), fin.getBlockY() - 2, fin.getBlockZ(), 8, 8, Material.GOLD_BLOCK);
        getConfig().set("arena.floor-y", fin.getBlockY() - 40);
        saveConfig();
        return arena.locs("spawns").get(0);
    }
}
