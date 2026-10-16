package nl.kmc.laser;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.laser.LaserBoard.Comp;
import nl.kmc.laser.LaserBoard.Type;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Laser Logic: every team has its own puzzle board next to the others. Mirrors are real blocks that can be
 * turned with a right-click; the logical beam ({@link LaserSim}) decides what is solved, particles only show
 * it. Boards are drawn into the world when the game starts and restored when it ends.
 */
public final class LaserLogicManager extends ArcadeGameManager {

    // ── Per-team state ────────────────────────────────────────────────────────

    /** Every rotatable mirror gets its own colour each puzzle, so a hint can say "the red mirror". */
    private enum MirrorColor {
        RED(Material.RED_STAINED_GLASS, "§c", "red"), ORANGE(Material.ORANGE_STAINED_GLASS, "§6", "orange"),
        YELLOW(Material.YELLOW_STAINED_GLASS, "§e", "yellow"), LIME(Material.LIME_STAINED_GLASS, "§a", "lime"),
        CYAN(Material.CYAN_STAINED_GLASS, "§3", "cyan"), BLUE(Material.BLUE_STAINED_GLASS, "§9", "blue"),
        PURPLE(Material.PURPLE_STAINED_GLASS, "§5", "purple"), PINK(Material.PINK_STAINED_GLASS, "§d", "pink"),
        WHITE(Material.WHITE_STAINED_GLASS, "§f", "white"), BROWN(Material.BROWN_STAINED_GLASS, "§8", "brown");

        final Material glass;
        final String code;
        final String key;
        MirrorColor(Material glass, String code, String key) { this.glass = glass; this.code = code; this.key = key; }
    }

    private static final class TeamState {
        int boardIndex;
        PuzzleDef def;
        LaserBoard board;
        LaserSim.Result trace;
        Map<String, Boolean> solution;
        long startedTick;
        long nextAt;               // when the next puzzle replaces a solved one
        boolean locked;            // solved, waiting for the next puzzle
        int number;                // puzzles shown so far (1-based, also the key of rewards)
        int hints;                 // hints used on the current puzzle
        int solved, points, hintsTotal;
        long solveTicks;
        final int[] byDifficulty = new int[3];
        final Set<String> used = new HashSet<>();
        BoardView view;
        boolean beamDirty = true;
    }

    /** Blocks and displays of one board, with the original blocks to put back. */
    private final class BoardView {
        final Location origin;
        final Map<Long, BlockData> original = new HashMap<>();
        final Map<Long, Block> blocks = new HashMap<>();
        final List<TextDisplay> labels = new ArrayList<>();
        final Map<Long, TextDisplay> mirrorLabels = new HashMap<>();
        int w, h;
        /** Colour of each rotatable mirror of the board that is shown, by "x,y". */
        Map<String, MirrorColor> colors = new HashMap<>();

        BoardView(Location origin) { this.origin = origin; }

        private Block at(int x, int dy, int y) {
            return origin.getWorld().getBlockAt(origin.getBlockX() + x, origin.getBlockY() + dy, origin.getBlockZ() + y);
        }

        private void put(Block b, Material m) {
            long k = key(b.getX(), b.getY(), b.getZ());
            original.putIfAbsent(k, b.getBlockData());
            blocks.put(k, b);
            b.setType(m, false);
        }

        void clear() {
            for (TextDisplay d : labels) if (d.isValid()) d.remove();
            labels.clear();
            mirrorLabels.clear();
            for (var en : blocks.entrySet()) {
                BlockData d = original.get(en.getKey());
                if (d != null) en.getValue().setBlockData(d, false);
            }
            blocks.clear();
            original.clear();
        }

        void render(LaserBoard b) {
            clear();
            w = b.width();
            h = b.height();
            Material floor = parseMaterial(gamePlugin.getConfig().getString("board.floor-material", "SMOOTH_QUARTZ"), Material.SMOOTH_QUARTZ);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    put(at(x, -1, y), floor);
                    put(at(x, 1, y), Material.AIR);
                    Comp c = b.get(x, y);
                    Block cell = at(x, 0, y);
                    if (c == null || c.type == Type.EMPTY) { put(cell, Material.AIR); continue; }
                    switch (c.type) {
                        case WALL -> put(cell, Material.DEEPSLATE_BRICKS);
                        case ABSORBER -> put(cell, Material.BLACK_CONCRETE);
                        case SPLITTER -> put(cell, Material.PRISMARINE_BRICKS);
                        case EMITTER -> {
                            put(cell, Material.SEA_LANTERN);
                            label(x, y, "§c§l" + arrow(c.dir), 0.4f);
                        }
                        case MIRROR -> {
                            MirrorColor mc = c.rotatable ? colors.get(x + "," + y) : null;
                            put(cell, mc != null ? mc.glass : Material.TINTED_GLASS);
                            TextDisplay d = label(x, y, mirrorText(c, mc), 0.4f);
                            mirrorLabels.put(cellKey(x, y), d);
                        }
                        case FILTER -> put(cell, filterGlass(c.color));
                        case RECEIVER -> {
                            put(cell, Material.REDSTONE_LAMP);
                            label(x, y, "§e§l◎ §7" + (c.needColor != null ? c.needColor : ""), 0.4f);
                        }
                        default -> put(cell, Material.AIR);
                    }
                }
            }
        }

        TextDisplay label(int x, int y, String text, float yOff) {
            Location l = origin.clone().add(x + 0.5, 1.0 + yOff, y + 0.5);
            TextDisplay d = origin.getWorld().spawn(l, TextDisplay.class, t -> {
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setSeeThrough(true);
                t.text(Component.text(text));
            });
            labels.add(d);
            return d;
        }

        void updateMirror(int x, int y, Comp c) {
            TextDisplay d = mirrorLabels.get(cellKey(x, y));
            if (d != null && d.isValid()) d.text(Component.text(mirrorText(c, colors.get(x + "," + y))));
        }

        void lamp(int x, int y, boolean on) {
            Block b = at(x, 0, y);
            if (b.getBlockData() instanceof Lightable l && l.isLit() != on) {
                l.setLit(on);
                b.setBlockData(l, false);
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final LaserLogicPlugin plugin;
    private final Map<String, TeamState> teams = new LinkedHashMap<>();
    private final Map<Long, String[]> mirrorAt = new HashMap<>();      // block key -> {team, x, y}
    private final List<PuzzleDef> pool = new ArrayList<>();
    private final Map<Integer, String> firstSolved = new HashMap<>();   // puzzle number -> first team to solve it
    private final Cooldowns cooldowns = new Cooldowns();
    private final Random rnd = new Random();
    private NamespacedKey hintKey;
    private long durationTicks;

    public LaserLogicManager(LaserLogicPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static long cellKey(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    private static Material parseMaterial(String s, Material def) {
        Material m = Material.matchMaterial(s == null ? "" : s);
        return m != null ? m : def;
    }

    private static String arrow(LaserBoard.Dir d) {
        return switch (d) { case N -> "↑"; case E -> "→"; case S -> "↓"; case W -> "←"; };
    }

    private static String mirrorText(Comp c, MirrorColor mc) {
        String lock = c.unlockedBy != null ? "§8🔒 " : "";
        String tint = c.rotatable ? (mc != null ? mc.code : "§b") + "§l" : "§7§l";
        return lock + tint + (c.slash ? "╱" : "╲");
    }

    /** A different colour for every rotatable mirror, shuffled again for every puzzle. */
    private Map<String, MirrorColor> assignColors(LaserBoard board) {
        List<MirrorColor> palette = new ArrayList<>(List.of(MirrorColor.values()));
        Collections.shuffle(palette, rnd);
        Map<String, MirrorColor> out = new HashMap<>();
        int i = 0;
        for (int[] m : LaserSim.rotatableMirrors(board)) out.put(m[0] + "," + m[1], palette.get(i++ % palette.size()));
        return out;
    }


    private static Material filterGlass(String color) {
        String name = (color == null ? "WHITE" : color.toUpperCase(Locale.ROOT)) + "_STAINED_GLASS";
        return parseMaterial(name, Material.WHITE_STAINED_GLASS);
    }

    private static Color particleColor(String c) {
        return switch (c == null ? "WHITE" : c.toUpperCase(Locale.ROOT)) {
            case "RED" -> Color.RED;
            case "GREEN" -> Color.LIME;
            case "BLUE" -> Color.AQUA;
            case "YELLOW" -> Color.YELLOW;
            case "PURPLE" -> Color.PURPLE;
            case "ORANGE" -> Color.ORANGE;
            default -> Color.WHITE;
        };
    }

    private int cfgInt(String path, int def) { return gamePlugin.getConfig().getInt(path, def); }

    /** The puzzles from config.yml, parsed; problems are collected for the validator. */
    List<PuzzleDef> loadPuzzles(List<String> problems) {
        List<PuzzleDef> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object o : gamePlugin.getConfig().getList("puzzles", List.of())) {
            if (!(o instanceof Map<?, ?> m)) { problems.add("puzzles: ongeldige regel " + o); continue; }
            PuzzleDef.Difficulty d;
            try { d = PuzzleDef.Difficulty.valueOf(String.valueOf(m.get("difficulty")).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { d = PuzzleDef.Difficulty.EASY; }
            PuzzleDef def = PuzzleDef.parse(m, pointsFor(d), cfgInt("game.puzzle-seconds", 120), problems);
            if (def == null) continue;
            if (!ids.add(def.id())) { problems.add("puzzle id '" + def.id() + "' komt twee keer voor"); continue; }
            problems.addAll(def.validate());
            out.add(def);
        }
        return out;
    }

    private int pointsFor(PuzzleDef.Difficulty d) {
        return switch (d) {
            case EASY -> cfgInt("points.easy", 100);
            case MEDIUM -> cfgInt("points.medium", 175);
            case HARD -> cfgInt("points.hard", 275);
        };
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§b§lLASER LOGIC"; }
    @Override protected String introSubKey() { return "@laser.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@laser.tut.1", "@laser.tut.2", "@laser.tut.3", "@laser.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/laser world)");
        if (arena.locs("boards").isEmpty()) p.add("geen puzzelborden (/laser board add)");
        List<String> pp = new ArrayList<>();
        List<PuzzleDef> defs = loadPuzzles(pp);
        p.addAll(pp);
        if (defs.isEmpty() && !gamePlugin.getConfig().getBoolean("generator.enabled", true))
            p.add("geen puzzels in config.yml en de generator staat uit");
        return p;
    }

    @Override
    protected List<Location> spawnPoints() {
        List<Location> out = new ArrayList<>();
        var off = gamePlugin.getConfig().getDoubleList("board.spawn-offset");
        double dx = off.size() > 0 ? off.get(0) : -2, dy = off.size() > 1 ? off.get(1) : 0, dz = off.size() > 2 ? off.get(2) : 4;
        for (Location b : arena.locs("boards")) out.add(b.clone().add(dx, dy, dz));
        return out;
    }

    @Override
    protected Location flyoverCenter() {
        List<Location> b = arena.locs("boards");
        if (b.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location l : b) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        return new Location(b.get(0).getWorld(), x / b.size() + 5, y / b.size() + 10, z / b.size() + 5);
    }

    // ── Preparation / start ───────────────────────────────────────────────────

    @Override
    protected void prepareGame() {
        teams.clear();
        mirrorAt.clear();
        firstSolved.clear();
        cooldowns.clear();
        pool.clear();
        pool.addAll(loadPuzzles(new ArrayList<>()));
        hintKey = new NamespacedKey(gamePlugin, "hint");
        durationTicks = cfgInt("game.duration-seconds", 420) * 20L;

        List<Location> boards = arena.locs("boards");
        List<String> ids = activeTeams();
        if (ids.size() > boards.size()) {
            String msg = "§c[Laser Logic] " + ids.size() + " teams maar " + boards.size()
                    + " puzzelborden — elk team heeft een eigen bord nodig (/laser board add).";
            for (Player op : Bukkit.getOnlinePlayers()) if (op.isOp() || op.hasPermission("laserlogic.admin")) op.sendMessage(msg);
            log.severe(msg);
            Bukkit.getScheduler().runTask(gamePlugin, this::end);
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            TeamState ts = new TeamState();
            ts.boardIndex = i;
            ts.view = new BoardView(boards.get(i));
            teams.put(ids.get(i), ts);
        }
    }

    @Override
    protected void beginPlay() {
        for (var en : teams.entrySet()) startPuzzle(en.getKey(), en.getValue());
        for (Player p : activePlayers()) giveHintItem(p);
    }

    private PuzzleDef nextDefinition(TeamState ts, int number) {
        List<String> order = gamePlugin.getConfig().getStringList("game.progression");
        PuzzleDef.Difficulty want = PuzzleDef.Difficulty.EASY;
        if (!order.isEmpty()) {
            try { want = PuzzleDef.Difficulty.valueOf(order.get(Math.min(number - 1, order.size() - 1)).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        // the shipped EASY puzzles were far too easy: every puzzle comes one step up (game.difficulty-shift)
        int shift = Math.max(0, cfgInt("game.difficulty-shift", 1));
        for (int i = 0; i < shift && want != PuzzleDef.Difficulty.HARD; i++)
            want = want == PuzzleDef.Difficulty.EASY ? PuzzleDef.Difficulty.MEDIUM : PuzzleDef.Difficulty.HARD;
        List<PuzzleDef> fresh = new ArrayList<>(), any = new ArrayList<>();
        for (PuzzleDef d : pool) {
            if (d.difficulty() != want) continue;
            any.add(d);
            if (!ts.used.contains(d.id())) fresh.add(d);
        }
        if (!fresh.isEmpty()) return fresh.get(rnd.nextInt(fresh.size()));
        // the configured puzzles of this difficulty are used up: a generated one (solution first), else a repeat
        if (gamePlugin.getConfig().getBoolean("generator.enabled", true)) {
            int turns = want == PuzzleDef.Difficulty.EASY ? 3 : want == PuzzleDef.Difficulty.MEDIUM ? 6 : 9;
            int size = want == PuzzleDef.Difficulty.EASY ? 9 : want == PuzzleDef.Difficulty.MEDIUM ? 11 : 13;

            PuzzleDef g = PuzzleGenerator.generate("gen" + number, want, size, size, turns, pointsFor(want),
                    cfgInt("game.puzzle-seconds", 120), rnd);
            if (g != null) return g;
        }
        if (!any.isEmpty()) return any.get(rnd.nextInt(any.size()));
        if (!pool.isEmpty()) return pool.get(rnd.nextInt(pool.size()));
        return null;
    }

    private void startPuzzle(String team, TeamState ts) {
        // clear the lookups of the previous board
        mirrorAt.values().removeIf(v -> v[0].equals(team));
        ts.number++;
        PuzzleDef def = nextDefinition(ts, ts.number);
        if (def == null) { log.severe("[LaserLogic] geen puzzel beschikbaar voor " + team); return; }
        ts.used.add(def.id());
        ts.def = def;
        ts.board = def.board().copy();
        ts.trace = LaserSim.trace(ts.board, PuzzleDef.MAX_STEPS);
        ts.solution = LaserSim.solve(ts.board, PuzzleDef.MAX_STEPS);
        ts.startedTick = ticks;
        ts.locked = false;
        ts.hints = 0;
        ts.beamDirty = true;
        ts.view.colors = assignColors(ts.board);
        ts.view.render(ts.board);
        for (int[] m : ts.board.find(Type.MIRROR)) {
            Location l = ts.view.origin;
            mirrorAt.put(key(l.getBlockX() + m[0], l.getBlockY(), l.getBlockZ() + m[1]),
                    new String[]{team, String.valueOf(m[0]), String.valueOf(m[1])});
        }
        syncLamps(ts);
        for (UUID id : membersOf(team)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendTitle(api.tr(id, "laser.puzzle.title", ts.number), api.tr(id, "laser.puzzle.sub",
                    api.tr(id, "laser.diff." + def.difficulty().name().toLowerCase(Locale.ROOT)), def.points(), def.timeLimitSeconds()),
                    5, 50, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.3f);
        }
    }

    private void syncLamps(TeamState ts) {
        for (int[] r : ts.board.find(Type.RECEIVER)) {
            Comp c = ts.board.get(r[0], r[1]);
            ts.view.lamp(r[0], r[1], c.id != null && ts.trace.isActive(c.id));
        }
    }

    // ── Hint item ─────────────────────────────────────────────────────────────

    private boolean isHintItem(ItemStack it) {
        return it != null && it.getType() == Material.PAPER && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(hintKey, PersistentDataType.BYTE);
    }

    private void giveHintItem(Player p) {
        if (!gamePlugin.getConfig().getBoolean("hints.enabled", true)) return;
        for (ItemStack it : p.getInventory().getContents()) if (isHintItem(it)) return;
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(api.tr(p.getUniqueId(), "laser.hint.item"));
        meta.setLore(List.of(api.tr(p.getUniqueId(), "laser.hint.lore", cfgInt("hints.max-per-puzzle", 3),
                cfgInt("points.no-hint-bonus", 15))));
        meta.getPersistentDataContainer().set(hintKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        p.getInventory().setItem(8, item);
    }

    private void useHint(Player p) {
        UUID id = p.getUniqueId();
        String team = teamId(id);
        TeamState ts = teams.get(team);
        if (ts == null || ts.locked || ts.board == null) return;
        int max = cfgInt("hints.max-per-puzzle", 3);
        if (ts.hints >= max) { actionBar(p, api.tr(id, "laser.hint.max")); return; }
        if (!cooldowns.ready("hint:" + team, ticks)) return;
        cooldowns.start("hint:" + team, ticks, 40);
        ts.hints++;
        ts.hintsTotal++;
        String lost = ts.hints == 1 && cfgInt("points.no-hint-bonus", 15) > 0 ? api.tr(id, "laser.hint.lost") : "";

        List<String> want = new ArrayList<>();
        for (String r : ts.board.goalReceivers()) if (!ts.trace.isActive(r)) want.add(r);
        String text;
        if (ts.hints == 1) {
            text = api.tr(id, "laser.hint.1", want.isEmpty() ? "-" : String.join(", ", want));
        } else if (ts.hints == 2) {
            int[] wrong = null;
            if (ts.solution != null) {
                for (int[] m : LaserSim.rotatableMirrors(ts.board)) {
                    Boolean s = ts.solution.get(m[0] + "," + m[1]);
                    if (s != null && s != ts.board.get(m[0], m[1]).slash) { wrong = m; break; }
                }
            }
            MirrorColor wc = wrong != null ? ts.view.colors.get(wrong[0] + "," + wrong[1]) : null;
            String colourName = wc != null ? wc.code + api.tr(id, "laser.color." + wc.key) + "§f" : "";
            text = wrong != null ? api.tr(id, "laser.hint.2", wrong[0] + 1, wrong[1] + 1, colourName) : api.tr(id, "laser.hint.2-none");

            if (wrong != null) pulse(ts, wrong[0], wrong[1]);
        } else {
            text = api.tr(id, "laser.hint.3");
            showSolutionPath(ts);
        }
        for (UUID m : membersOf(team)) {
            Player mp = Bukkit.getPlayer(m);
            if (mp != null) mp.sendMessage(api.tr(m, "laser.hint.line", ts.hints, max, text) + lost);
        }
    }

    private void pulse(TeamState ts, int x, int y) {
        Location l = ts.view.origin.clone().add(x + 0.5, 1.2, y + 0.5);
        l.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l, 20, 0.3, 0.3, 0.3, 0);
    }

    /** Hint 3: the first stretch of the solution is drawn for a moment, the rest stays a puzzle. */
    private void showSolutionPath(TeamState ts) {
        if (ts.solution == null) return;
        LaserBoard solved = ts.board.copy();
        for (var e : ts.solution.entrySet()) {
            String[] p = e.getKey().split(",");
            Comp c = solved.get(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            if (c != null) c.slash = e.getValue();
        }
        var res = LaserSim.trace(solved, PuzzleDef.MAX_STEPS);
        int shown = Math.max(2, res.segments.size() / 2);
        World w = ts.view.origin.getWorld();
        for (int i = 0; i < shown && i < res.segments.size(); i++) {
            var s = res.segments.get(i);
            w.spawnParticle(Particle.END_ROD, ts.view.origin.clone().add(s.x() + 0.5, 0.9, s.y() + 0.5), 2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !live || !getState().isRunning()) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!isParticipant(id)) return;
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) && isHintItem(e.getItem())) {
            e.setCancelled(true);
            if (isActive(id)) useHint(p);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        var b = e.getClickedBlock();
        String[] at = mirrorAt.get(key(b.getX(), b.getY(), b.getZ()));
        if (at == null) return;
        e.setCancelled(true);
        if (!isActive(id)) return;                                       // spectators and downed players
        if (!at[0].equals(teamId(id))) {                                 // somebody else's board
            actionBar(p, api.tr(id, "laser.foreign"));
            return;
        }
        rotate(p, at[0], Integer.parseInt(at[1]), Integer.parseInt(at[2]));
    }

    private void rotate(Player p, String team, int x, int y) {
        UUID id = p.getUniqueId();
        TeamState ts = teams.get(team);
        if (ts == null || ts.locked || ts.board == null) return;
        Comp c = ts.board.get(x, y);
        if (c == null || c.type != Type.MIRROR) return;
        if (!c.rotatable) { actionBar(p, api.tr(id, "laser.fixed")); return; }
        if (!LaserSim.canRotate(c, ts.trace)) { actionBar(p, api.tr(id, "laser.locked", c.unlockedBy)); return; }
        String ck = id + ":rot";
        if (!cooldowns.ready(ck, ticks)) return;
        cooldowns.start(ck, ticks, Math.max(0, cfgInt("rules.rotate-cooldown-ticks", 4)));

        c.slash = !c.slash;
        ts.view.updateMirror(x, y, c);
        ts.trace = LaserSim.trace(ts.board, PuzzleDef.MAX_STEPS);
        syncLamps(ts);
        ts.beamDirty = true;
        p.playSound(p.getLocation(), Sound.BLOCK_COMPARATOR_CLICK, 0.8f, 1.4f);
        if (LaserSim.solved(ts.board, ts.trace)) completePuzzle(team, ts);
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private void completePuzzle(String team, TeamState ts) {
        ts.locked = true;
        ts.nextAt = ticks + 60;
        long solveTicks = ticks - ts.startedTick;
        ts.solved++;
        ts.solveTicks += solveTicks;
        ts.byDifficulty[ts.def.difficulty().ordinal()]++;

        List<UUID> crew = membersOf(team);
        int base = ts.def.points();
        int first = 0;
        if (firstSolved.putIfAbsent(ts.number, team) == null) first = Math.max(0, cfgInt("points.first-completion-bonus", 25));
        int noHint = ts.hints == 0 ? Math.max(0, cfgInt("points.no-hint-bonus", 15)) : 0;
        String key = "pz:" + team + ":" + ts.number;
        awardShared(key + ":core", team, crew, base, PointAward.Reason.OBJECTIVE, "puzzle");
        awardShared(key + ":first", team, crew, first, PointAward.Reason.BONUS, "first");
        awardShared(key + ":nohint", team, crew, noHint, PointAward.Reason.BONUS, "no-hint");
        reportObjective(null, team, nl.kmc.core.event.GameObjectiveEvent.Type.PUZZLE_SOLVED);
        int total = base + first + noHint;
        ts.points += total;

        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            if (!isParticipant(vid)) continue;
            if (team.equals(teamId(vid))) {
                v.sendMessage(api.tr(vid, "laser.solved", ts.number, base, first, noHint, total, solveTicks / 20));
                v.sendTitle("§a§l✔ " + total, "§7" + (solveTicks / 20) + "s", 0, 40, 10);
                v.playSound(v.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else {
                v.sendMessage(api.tr(vid, "laser.solved-other", teamName(team), ts.number));
            }
        }
        // the whole beam path in celebration
        ts.beamDirty = true;
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        for (var en : teams.entrySet()) {
            TeamState ts = en.getValue();
            if (ts.def == null) continue;
            if (ts.locked) {
                if (ticks >= ts.nextAt) startPuzzle(en.getKey(), ts);
            } else if (ticks - ts.startedTick >= ts.def.timeLimitSeconds() * 20L) {
                for (UUID m : membersOf(en.getKey())) {
                    Player mp = Bukkit.getPlayer(m);
                    if (mp != null) mp.sendMessage(api.tr(m, "laser.timeout", ts.number));
                }
                startPuzzle(en.getKey(), ts);
            }
            if (ticks % 10 == 0 && ts.board != null) drawBeam(ts);
        }
        long left = Math.max(0, durationTicks - ticks) / 20;
        bar("§b§lLASER LOGIC §8| §e" + mmss(left), 1.0 - (double) ticks / Math.max(1, durationTicks),
                left <= 30 ? BarColor.RED : BarColor.BLUE);
        if (ticks >= durationTicks) end();
    }

    private void drawBeam(TeamState ts) {
        if (ts.trace == null) return;
        World w = ts.view.origin.getWorld();
        int density = Math.max(1, Math.min(4, cfgInt("effects.particle-density", 2)));
        int drawn = 0;
        for (LaserSim.Seg s : ts.trace.segments) {
            if (drawn++ > 160) break;
            Particle.DustOptions dust = new Particle.DustOptions(particleColor(s.color()), 1.3f);
            w.spawnParticle(Particle.DUST, ts.view.origin.clone().add(s.x() + 0.5, 0.5, s.y() + 0.5), density,
                    0.15, 0.05, 0.15, 0, dust);
        }
        for (int[] em : ts.board.find(Type.EMITTER)) {
            Comp c = ts.board.get(em[0], em[1]);
            w.spawnParticle(Particle.DUST, ts.view.origin.clone().add(em[0] + 0.5, 0.5, em[1] + 0.5), density + 1,
                    0.2, 0.2, 0.2, 0, new Particle.DustOptions(particleColor(c.color), 1.6f));
        }
    }

    // ── HUD / result ──────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        TeamState ts = teams.get(team);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "laser.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (ts != null && ts.def != null) {
            long left = Math.max(0, ts.def.timeLimitSeconds() * 20L - (ticks - ts.startedTick)) / 20;
            l.add(api.tr(id, "laser.sb.puzzle", ts.number,
                    api.tr(id, "laser.diff." + ts.def.difficulty().name().toLowerCase(Locale.ROOT))));
            l.add(api.tr(id, "laser.sb.left", ts.locked ? 0 : left));
            l.add(api.tr(id, "laser.sb.solved", ts.solved));
            l.add(api.tr(id, "laser.sb.hints", ts.hints, cfgInt("hints.max-per-puzzle", 3)));
        }
        l.add("");
        l.add(api.tr(id, "laser.sb.team", shownTeamPoints(team)));
        return l;
    }

    @Override
    protected Result finishGame() {
        List<String> ranked = book.rankTeams(teams.keySet());
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) order.addAll(membersOf(t));
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "laser.results"));
            int i = 1;
            for (String t : ranked) {
                TeamState ts = teams.get(t);
                if (ts == null) continue;
                long avg = ts.solved > 0 ? ts.solveTicks / ts.solved / 20 : 0;
                v.sendMessage(api.tr(vid, "laser.result-line", i++, teamName(t), shownTeamPoints(t), ts.solved,
                        ts.byDifficulty[0], ts.byDifficulty[1], ts.byDifficulty[2], avg, ts.hintsTotal));
            }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        UUID mvp = order.isEmpty() ? null : order.get(0);
        return new Result(winner, mvp, mvp != null ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        for (TeamState ts : teams.values()) if (ts.view != null) ts.view.clear();
        mirrorAt.clear();
        teams.clear();
    }
}
