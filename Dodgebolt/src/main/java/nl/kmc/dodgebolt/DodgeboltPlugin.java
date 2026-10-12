package nl.kmc.dodgebolt;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.dodgebolt.commands.DodgeboltCommand;
import nl.kmc.dodgebolt.listeners.DodgeboltListener;
import nl.kmc.dodgebolt.managers.ArenaConfig;
import nl.kmc.dodgebolt.managers.ArenaConfig.Side;
import nl.kmc.dodgebolt.managers.DodgeboltGameManagerV2;
import nl.kmc.game.api.AbstractGamePlugin;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.TestArenaKit;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Dodgebolt — the KMC finale game: the top two teams, one bow each platform, hit = out.
 * Not part of the voting rotation ({@code games.list.dodgebolt.finale-only}); see {@code automation.finale}.
 */
public final class DodgeboltPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "dodgebolt";

    private ArenaConfig arena;
    private DodgeboltGameManagerV2 gameManager;

    @Override protected String   gameId()      { return GAME_ID; }
    @Override protected String   displayName() { return "Dodgebolt"; }
    @Override protected Material icon()        { return Material.BOW; }
    @Override protected int      minPlayers()  { return 2; }
    @Override protected String   description() { return "Twee teams, twee platforms, één pijl-duel — een treffer en je ligt eruit."; }
    @Override protected String   objective()   { return "Win de meeste rondes door het andere team uit te schakelen."; }
    @Override protected List<String> scoringLines() {
        return List.of(
                "+" + getConfig().getInt("points.per-kill", 25) + " ptn — per uitgeschakelde tegenstander",
                "+" + getConfig().getInt("points.round-win", 50) + " ptn — elke ronde die je team wint",
                "+" + getConfig().getInt("points.match-win", 150) + " ptn — het hele duel winnen");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        gameManager = new DodgeboltGameManagerV2(this, reg, stats);
        return gameManager;
    }

    @Override
    protected void onGameEnable() {
        // The finale game is never part of the vote.
        var core = getKmcCore().getConfig();
        String base = "games.list." + GAME_ID + ".";
        if (!core.isSet(base + "finale-only")) {
            core.set(base + "finale-only", true);
            core.set(base + "estimated-minutes", 8);
            getKmcCore().saveConfig();
        }

        var cmd = new DodgeboltCommand(this);
        var bukkitCmd = getCommand("dodgebolt");
        if (bukkitCmd != null) { bukkitCmd.setExecutor(cmd); bukkitCmd.setTabCompleter(cmd); }
        getServer().getPluginManager().registerEvents(new DodgeboltListener(this), this);
    }

    @Override
    protected void onGameDisable() {
        if (gameManager != null && gameManager.isRunning()) gameManager.end();
    }

    @Override protected void onV1GameStart(String gameId) { /* V2 only */ }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** Two 9x9 platforms 17 blocks apart over the void, four spawns each. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();

        int half = 4, gap = 8;   // platform half-width, half-distance between the platform centres
        for (int side : new int[]{-1, 1}) {
            int px = cx + side * (gap + half);
            TestArenaKit.fill(w, px - half, y, cz - half, px + half, y, cz + half,
                    side < 0 ? Material.RED_CONCRETE : Material.BLUE_CONCRETE);
        }

        arena.clearSpawns(Side.A);
        arena.clearSpawns(Side.B);
        for (int i = 0; i < 4; i++) {
            double dz = (i - 1.5) * 2.0;
            arena.addSpawn(Side.A, new Location(w, cx - (gap + half) + 0.5, y + 1, cz + dz + 0.5, -90f, 0f));
            arena.addSpawn(Side.B, new Location(w, cx + (gap + half) + 0.5, y + 1, cz + dz + 0.5, 90f, 0f));
        }
        arena.setDrop(Side.A, new Location(w, cx - (gap + half) + 0.5, y + 1.5, cz + 0.5));
        arena.setDrop(Side.B, new Location(w, cx + (gap + half) + 0.5, y + 1.5, cz + 0.5));
        Location spec = new Location(w, cx + 0.5, y + 8, cz + 0.5, 0f, 20f);
        arena.setSpectator(spec);
        arena.setCenter(new Location(w, cx + 0.5, y + 4, cz + 0.5));
        arena.setVoidY(y - 8);
        return new Location(w, cx - (gap + half) + 0.5, y + 1, cz + 0.5, -90f, 0f);
    }

    public ArenaConfig getArena() { return arena; }
    public DodgeboltGameManagerV2 getGameManagerV2() { return gameManager; }
}
