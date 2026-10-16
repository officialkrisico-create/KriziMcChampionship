package nl.kmc.factory;

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
 * Factory Frenzy: team logistics. Setup: {@code /factory world}, then stand on the origin block of every
 * team's factory and run {@code /factory factory add}. Where machines, stations and the delivery point lie
 * relative to that origin is in config.yml ({@code layout}), so identical factories need no further setup.
 */
public final class FactoryFrenzyPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "factory_frenzy";

    private ArenaConfig arena;
    private FactoryFrenzyManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Factory Frenzy"; }
    @Override protected Material icon() { return Material.BLAST_FURNACE; }
    @Override protected int minPlayers() { return 1; }
    @Override protected String description() { return "Haal grondstoffen, laat machines produceren en lever de bestellingen van je fabriek."; }
    @Override protected String objective() { return "Voltooi de meeste bestellingen met je fabriek."; }

    @Override
    protected List<String> scoringLines() {
        return List.of(
                "Beloning per voltooide bestelling (100-400 ptn, zie het bord)",
                "+10-25% bonus als de deadline van de bestelling gehaald is",
                "Punten gaan naar wie de producten heeft ingeleverd",
                placementLine() + " (teamplaats volgens KMC)");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new FactoryFrenzyManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        var cmd = new FactoryCommand();
        var c = getCommand("factory");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    private final class FactoryCommand extends ArcadeCommand {
        FactoryCommand() {
            super(FactoryFrenzyPlugin.this, FactoryFrenzyPlugin.this.arena,
                    () -> (ArcadeGameManager) FactoryFrenzyPlugin.this.manager, "factoryfrenzy.admin", "Factory Frenzy");
        }

        @Override protected List<String> problems() {
            return manager != null ? manager.getArenaIssues() : List.of("game niet beschikbaar");
        }

        @Override protected List<String> extraSubs() { return List.of("factory", "info", "test", "reload"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            return sub.equals("factory") && a.length == 2 ? List.of("add", "clear", "list") : List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            switch (sub) {
                case "reload" -> { reloadConfig(); ok(s, "Config herladen."); return true; }
                case "factory" -> { factory(s, a); return true; }
                case "info", "test" -> { info(s); return true; }
                default -> { return false; }
            }
        }

        private void info(CommandSender s) {
            var m = manager.loadModel();
            s.sendMessage("§6Factory Frenzy §7— fabrieken: §f" + arena.locs("factories").size() + " §7| machines: §f"
                    + m.machines.size() + " §7| stations: §f" + m.stations.size() + " §7| orders: §f" + m.orders.size()
                    + " §7| items: §f" + m.items.size());
            if (m.problems.isEmpty()) ok(s, "Productieketen en orders zijn geldig (elke order is te maken).");
            else m.problems.forEach(p -> err(s, p));
        }

        private void factory(CommandSender s, String[] a) {
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    Location at = p.getLocation().getBlock().getLocation();
                    int n = arena.addLoc("factories", at);
                    ok(s, "Fabriek #" + n + " (team " + n + " in alfabetische volgorde) heeft zijn oorsprong op " + at.getBlockX()
                            + ", " + at.getBlockY() + ", " + at.getBlockZ() + ".");
                }
                case "clear" -> { arena.clearLocs("factories"); ok(s, "Fabrieken gewist."); }
                default -> {
                    int i = 1;
                    for (Location l : arena.locs("factories"))
                        s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                    if (i == 1) s.sendMessage("§7Geen fabrieken.");
                }
            }
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /** Eight identical factory plots in two rows; the machines are placed from config.yml when the game starts. */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 46, y - 1, cz - 34, cx + 46, y + 8, cz + 34, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 44, 32, Material.SMOOTH_STONE);

        arena.setWorld(w);
        arena.clearLocs("factories");
        arena.clearLocs("spawns");
        for (int i = 0; i < 8; i++) {
            int col = i % 4, row = i / 4;
            int fx = cx - 33 + col * 22, fz = cz - 14 + row * 28;
            TestArenaKit.platform(w, fx, y, fz, 9, 11, i % 2 == 0 ? Material.WHITE_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
            arena.addLoc("factories", new Location(w, fx, y + 1, fz));
        }
        return arena.locs("factories").get(0).clone().add(0, 0, 6);
    }
}
