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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Crafting Countdown: a Build-Mart-style collecting game (no crafting, no building). Setup:
 * {@code /craftcountdown station add} (look at the delivery block of a team), {@code pile add <type>} (look
 * at the block that is the pile) — the types and their items are in config.yml ({@code pile-types}).
 */
public final class CraftingCountdownPlugin extends AbstractGamePlugin {

    public static final String GAME_ID = "crafting_countdown";

    private ArenaConfig arena;
    private CraftingCountdownManager manager;

    @Override protected String gameId() { return GAME_ID; }
    @Override protected String displayName() { return "Crafting Countdown"; }
    @Override protected Material icon() { return Material.CRAFTING_TABLE; }
    @Override protected int minPlayers() { return 1; }
    @Override protected String description() {
        return "Haal grondstoffen bij de stapels, craft de bestelde items en lever ze in bij je eigen inleverpunt.";
    }
    @Override protected String objective() { return "Craft en lever de meeste bestellingen als team."; }

    @Override
    protected List<String> scoringLines() {
        var c = getConfig();
        return List.of(
                "+" + c.getInt("points.base-per-order", 10) + " ptn + de waarde van de gecrafte items — per voltooide bestelling",
                "+" + c.getInt("points.speed-bonus", 25) + " ptn — snelheidsbonus voor een snelle bestelling",
                "+" + c.getInt("points.streak-per-step", 5) + " ptn per bestelling in een reeks (max +" + c.getInt("points.streak-cap", 25) + ")",
                "Punten zijn van de spelers die de items hebben ingeleverd");
    }

    @Override
    protected BaseGameManager createGameManagerV2(StatisticsService stats, GameRegistration reg) {
        arena = new ArenaConfig(this);
        manager = new CraftingCountdownManager(this, reg, stats, arena);
        return manager;
    }

    @Override
    protected void onGameEnable() {
        // A config.yml from an older version (recipes, or the first collecting version) gets the current
        // tuning sections; the arena data (world, stations, piles, spawns) is left alone.
        if (getConfig().getInt("config-version", 0) < 2) {
            for (String sec : List.of("game", "crafting", "piles", "pile-types", "orders", "item-pool", "points",
                    "recipes", "start-sequence"))
                getConfig().set(sec, null);
            getConfig().set("config-version", null);
            getConfig().options().copyDefaults(true);
            saveConfig();
            reloadConfig();
            getLogger().info("[CraftingCountdown] config.yml updated to the gather-and-craft version (config-version 2).");
        }
        var cmd = new CraftCommand();
        var c = getCommand("craftcountdown");
        if (c != null) { c.setExecutor(cmd); c.setTabCompleter(cmd); }
    }

    @Override protected void onGameDisable() { if (manager != null && manager.isRunning()) manager.end(); }
    @Override protected void onV1GameStart(String gameId) { }

    private List<String> pileTypeIds() {
        ConfigurationSection s = getConfig().getConfigurationSection("pile-types");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private final class CraftCommand extends ArcadeCommand {
        CraftCommand() {
            super(CraftingCountdownPlugin.this, CraftingCountdownPlugin.this.arena,
                    () -> (ArcadeGameManager) CraftingCountdownPlugin.this.manager, "craftcountdown.admin", "Crafting Countdown");
        }

        @Override
        protected List<String> problems() {
            return manager != null ? manager.getArenaIssues() : List.of("game niet beschikbaar");
        }

        @Override protected List<String> extraSubs() { return List.of("station", "pile", "reload"); }

        @Override
        protected List<String> extraTab(String sub, String[] a) {
            if (sub.equals("station") && a.length == 2) return List.of("add", "clear", "list");
            if (sub.equals("pile") && a.length == 2) return List.of("add", "remove", "clear", "list", "types");
            if (sub.equals("pile") && a.length == 3 && (a[1].equalsIgnoreCase("add") || a[1].equalsIgnoreCase("clear")))
                return pileTypeIds();
            return List.of();
        }

        @Override
        protected boolean extra(CommandSender s, String sub, String[] a, String label) {
            if (sub.equals("reload")) { reloadConfig(); ok(s, "Config herladen."); return true; }
            if (sub.equals("station")) { station(s, a); return true; }
            if (sub.equals("pile")) { pile(s, a); return true; }
            return false;
        }

        private Block looking(Player p) {
            Block b = p.getTargetBlockExact(6);
            if (b == null) p.sendMessage("§c[Crafting Countdown] Kijk naar een blok (binnen 6 blokken).");
            return b;
        }

        private void station(CommandSender s, String[] a) {
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    Block b = looking(p);
                    if (b == null) return;
                    int n = arena.addLoc("stations", b.getLocation().add(0.5, 0, 0.5));
                    ok(s, "Inleverpunt #" + n + " gezet op het blok waar je naar kijkt (team " + n
                            + " in alfabetische volgorde van team-id).");
                }
                case "clear" -> { arena.clearLocs("stations"); ok(s, "Inleverpunten gewist."); }
                default -> {
                    int i = 1;
                    for (Location l : arena.locs("stations"))
                        s.sendMessage("§7#" + (i++) + " §f" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                    if (i == 1) s.sendMessage("§7Geen inleverpunten.");
                }
            }
        }

        private void pile(CommandSender s, String[] a) {
            String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            switch (act) {
                case "types" -> s.sendMessage("§7Stapel-types: §f" + String.join("§7, §f", pileTypeIds()));
                case "add" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    String type = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                    if (!pileTypeIds().contains(type)) {
                        err(s, "Onbekend type '" + type + "'. Kies uit: " + String.join(", ", pileTypeIds()));
                        return;
                    }
                    Block b = looking(p);
                    if (b == null) return;
                    int n = arena.addLoc("piles." + type, b.getLocation().add(0.5, 0, 0.5));
                    ok(s, "Stapel '" + type + "' #" + n + " gezet op het blok waar je naar kijkt.");
                }
                case "remove" -> {
                    if (!(s instanceof Player p)) { err(s, "Alleen spelers."); return; }
                    Block b = looking(p);
                    if (b == null) return;
                    boolean removed = false;
                    for (String type : pileTypeIds()) {
                        List<Location> all = arena.locs("piles." + type);
                        List<Location> keep = new ArrayList<>();
                        for (Location l : all) {
                            if (l.getBlockX() == b.getX() && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ()) removed = true;
                            else keep.add(l);
                        }
                        if (keep.size() != all.size()) {
                            arena.clearLocs("piles." + type);
                            for (Location l : keep) arena.addLoc("piles." + type, l);
                        }
                    }
                    if (removed) ok(s, "Stapel verwijderd."); else err(s, "Daar staat geen stapel.");
                }
                case "clear" -> {
                    if (a.length > 2) { arena.clearLocs("piles." + a[2].toLowerCase(Locale.ROOT)); ok(s, "Stapels van '" + a[2] + "' gewist."); }
                    else { arena.remove("piles"); ok(s, "Alle stapels gewist."); }
                }
                default -> {
                    int total = 0;
                    for (String type : pileTypeIds()) {
                        int n = arena.locs("piles." + type).size();
                        total += n;
                        if (n > 0) s.sendMessage("§7" + type + ": §f" + n);
                    }
                    s.sendMessage("§7Totaal: §f" + total + " §7stapels.");
                }
            }
        }
    }

    // ── /kmctest ──────────────────────────────────────────────────────────────

    @Override protected boolean supportsTestArena() { return true; }

    /**
     * A round plaza: eight delivery barrels on the rim (one per team) and six kinds of piles, two each, in
     * an inner ring — each pile is a block of its kind so the arena is readable without holograms.
     */
    @Override
    protected Location buildTestArena(Player admin, Location origin) {
        World w = origin.getWorld();
        Location a = TestArenaKit.anchor(origin);
        int cx = a.getBlockX(), cz = a.getBlockZ(), y = a.getBlockY();
        TestArenaKit.fill(w, cx - 32, y - 1, cz - 32, cx + 32, y + 8, cz + 32, Material.AIR);
        TestArenaKit.platform(w, cx, y, cz, 30, 30, Material.SMOOTH_STONE);

        arena.setWorld(w);
        arena.clearLocs("stations");
        arena.clearLocs("spawns");
        arena.remove("piles");

        int teams = 8;
        for (int i = 0; i < teams; i++) {
            double ang = Math.PI * 2 * i / teams;
            int sx = cx + (int) Math.round(Math.cos(ang) * 24), sz = cz + (int) Math.round(Math.sin(ang) * 24);
            TestArenaKit.platform(w, sx, y, sz, 2, 2, i % 2 == 0 ? Material.WHITE_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
            w.getBlockAt(sx, y + 1, sz).setType(Material.BARREL, false);
            w.getBlockAt(sx + (cx > sx ? 1 : -1), y + 1, sz).setType(Material.CRAFTING_TABLE, false);
            arena.addLoc("stations", new Location(w, sx + 0.5, y + 1, sz + 0.5));
            // a spot next to the barrel, facing the middle
            double yaw = Math.toDegrees(Math.atan2(-(cx - sx), cz - sz));
            arena.addLoc("spawns", TestArenaKit.stand(w, sx + (cx - sx) / 12.0 + 0.5, y + 1, sz + (cz - sz) / 12.0 + 0.5, (float) yaw));
        }

        String[] types = {"wood", "mine", "farm", "mobs", "market", "rare"};
        Material[] blocks = {Material.OAK_LOG, Material.COBBLESTONE, Material.HAY_BLOCK, Material.BONE_BLOCK,
                Material.BOOKSHELF, Material.DIAMOND_BLOCK};
        int piles = types.length * 2;
        for (int i = 0; i < piles; i++) {
            double ang = Math.PI * 2 * i / piles + 0.2;
            int px = cx + (int) Math.round(Math.cos(ang) * 13), pz = cz + (int) Math.round(Math.sin(ang) * 13);
            int t = i % types.length;
            TestArenaKit.platform(w, px, y, pz, 1, 1, Material.POLISHED_ANDESITE);
            w.getBlockAt(px, y + 1, pz).setType(blocks[t], false);
            arena.addLoc("piles." + types[t], new Location(w, px + 0.5, y + 1, pz + 0.5));
        }
        return arena.locs("spawns").get(0);
    }
}
