package nl.kmc.dungeon;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.dungeon.DungeonConfig.*;
import nl.kmc.dungeon.DungeonLogic.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.game.api.arcade.TemporaryBlocks;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Dungeon Draft: every team runs its own dungeon on its own lane. After each room the team drafts the next
 * one from a few options; rooms are combat, parkour, trap, treasure, puzzle and boss. A shared health pool
 * (with revives) decides when a run fails. All the rules of choosing live in {@link DungeonLogic}; this class
 * ties them to the world and cleans up every mob, block and boss bar when the room or the game ends.
 */
public final class DungeonDraftManager extends ArcadeGameManager {

    // ── Run state ─────────────────────────────────────────────────────────────

    private enum Stage { DRAFTING, ROOM, COMPLETED, FAILED }

    private static final class Draft {
        List<Option> options = new ArrayList<>();
        final Map<UUID, Integer> votes = new LinkedHashMap<>();
        long deadline;
        boolean locked;
    }

    private static final class Hazard {
        int[] min, max;
        long period, warn;
        double damage;
    }

    /** The state of the room a team is in; only the fields of its type are used. */
    private static final class RoomRun {
        RoomDef def;
        Modifier mod;
        Location origin;
        long started, limit;
        boolean complete;
        // combat
        final Set<UUID> mobs = new HashSet<>();
        final Deque<EntityType> pending = new ArrayDeque<>();
        int wave = -1, totalWaves;
        long nextWaveAt, lastSpawn;
        int kills;
        // parkour
        final Map<UUID, Integer> checkpoint = new HashMap<>();
        final Set<UUID> finished = new HashSet<>();
        // trap / treasure
        final Set<Long> pressed = new HashSet<>();
        final List<Hazard> hazards = new ArrayList<>();
        int total;
        // puzzle
        DungeonLogic.SequencePuzzle puzzle;
        long showUntil, nextStep;
        int showIndex;
        // boss
        LivingEntity boss;
        BossBar bar;
        long nextAttack, strikeAt;
        Location strikeAtLoc;
        boolean addsSummoned;
    }

    private static final class Run {
        String team;
        Location lane;
        Health health;
        Stage stage = Stage.DRAFTING;
        int roomNumber;                       // the number of the room that is current or being drafted
        final Set<String> used = new HashSet<>();
        Draft draft;
        RoomRun room;
        Option current;
        int currency, roomsDone, bosses, chests, deaths, points, bonus, highest, revivesUsed;
        long start, finishedAt;
    }

    private static final class DraftHolder implements InventoryHolder {
        final String team;
        Inventory inv;
        DraftHolder(String team) { this.team = team; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final DungeonDraftPlugin plugin;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final TemporaryBlocks blocks = new TemporaryBlocks();
    private final Map<Long, String[]> interactive = new HashMap<>();     // block key -> {team, kind, index}
    private List<RoomDef> pool = new ArrayList<>();
    private final Random rnd = new Random();
    private NamespacedKey mobKey;
    private long durationTicks;
    private BukkitTask pauseWatch;
    private boolean wasPaused;

    public DungeonDraftManager(DungeonDraftPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private int cfgInt(String p, int d) { return gamePlugin.getConfig().getInt(p, d); }
    private double cfgDouble(String p, double d) { return gamePlugin.getConfig().getDouble(p, d); }
    private boolean shared() { return !"INDIVIDUAL".equalsIgnoreCase(gamePlugin.getConfig().getString("health.mode", "SHARED")); }

    DungeonConfig.Parsed loadRooms() { return DungeonConfig.parse(gamePlugin.getConfig()); }

    private int totalRooms() { return Math.max(1, cfgInt("dungeon.length", 5)); }
    private int choices() { return Math.max(1, Math.min(5, cfgInt("dungeon.route-choices", 3))); }
    private boolean bossLast() { return gamePlugin.getConfig().getBoolean("dungeon.boss-last", true); }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static long key(Location l) { return key(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§5§lDUNGEON DRAFT"; }
    @Override protected String introSubKey() { return "@dungeon.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@dungeon.tut.1", "@dungeon.tut.2", "@dungeon.tut.3", "@dungeon.tut.4");
    }
    @Override protected Combat combat() { return shared() ? Combat.NONE : Combat.TEAM_PVP; }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/dungeon world)");
        if (arena.locs("lanes").isEmpty()) p.add("geen dungeon-banen (/dungeon lane add)");
        var parsed = loadRooms();
        p.addAll(parsed.problems);
        p.addAll(DungeonLogic.validate(parsed.rooms, totalRooms(), choices(), bossLast()));
        return p;
    }

    private Location lobbyOf(Location lane) {
        var off = gamePlugin.getConfig().getIntegerList("dungeon.lobby");
        int dx = off.size() > 0 ? off.get(0) : 6, dy = off.size() > 1 ? off.get(1) : 0, dz = off.size() > 2 ? off.get(2) : 0;
        return lane.clone().add(dx + 0.5, dy, dz + 0.5);
    }

    @Override
    protected List<Location> spawnPoints() {
        List<Location> out = new ArrayList<>();
        for (Location l : arena.locs("lanes")) out.add(lobbyOf(l));
        return out;
    }

    @Override
    protected Location flyoverCenter() {
        List<Location> l = arena.locs("lanes");
        if (l.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location v : l) { x += v.getX(); y += v.getY(); z += v.getZ(); }
        return new Location(l.get(0).getWorld(), x / l.size() + 30, y / l.size() + 14, z / l.size());
    }

    @Override
    protected Location respawnLocation(Player p) {
        Run r = runs.get(teamId(p.getUniqueId()));
        if (r != null && r.stage == Stage.ROOM && r.room != null) return entryOf(r.room);
        return r != null ? lobbyOf(r.lane) : p.getLocation();
    }

    // ── Preparation / start ───────────────────────────────────────────────────

    @Override
    protected void prepareGame() {
        runs.clear();
        interactive.clear();
        mobKey = new NamespacedKey(gamePlugin, "dd_mob");
        pool = loadRooms().rooms;
        durationTicks = cfgInt("dungeon.duration-seconds", 600) * 20L;

        List<Location> lanes = arena.locs("lanes");
        List<String> ids = activeTeams();
        if (ids.size() > lanes.size()) {
            String msg = "§c[Dungeon Draft] " + ids.size() + " teams maar " + lanes.size()
                    + " dungeon-banen — elk team heeft een eigen baan nodig (/dungeon lane add).";
            for (Player op : Bukkit.getOnlinePlayers()) if (op.isOp() || op.hasPermission("dungeondraft.admin")) op.sendMessage(msg);
            log.severe(msg);
            Bukkit.getScheduler().runTask(gamePlugin, this::end);
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            Run r = new Run();
            r.team = ids.get(i);
            r.lane = lanes.get(i);
            r.health = new Health(cfgInt("health.team-health", 100), cfgInt("health.revives", 1));
            runs.put(r.team, r);
        }
    }

    @Override
    protected void beginPlay() {
        wasPaused = false;
        pauseWatch = Bukkit.getScheduler().runTaskTimer(gamePlugin, this::watchPause, 10L, 10L);
        for (Run r : runs.values()) {
            r.start = ticks;
            startDraft(r);
        }
    }

    /** While the tournament is paused the mobs of every room stand still too (the loop itself is already paused). */
    private void watchPause() {
        boolean p = paused();
        if (p == wasPaused) return;
        wasPaused = p;
        for (Run r : runs.values()) {
            if (r.room == null) continue;
            for (UUID id : r.room.mobs) {
                if (Bukkit.getEntity(id) instanceof Mob m) m.setAI(!p);
            }
            if (r.room.boss instanceof Mob b) b.setAI(!p);
        }
    }

    // ── Drafting ──────────────────────────────────────────────────────────────

    private List<Player> teamPlayers(Run r) {
        List<Player> out = new ArrayList<>();
        for (UUID id : membersOf(r.team)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && isParticipant(id)) out.add(p);
        }
        return out;
    }

    private void startDraft(Run r) {
        r.roomNumber++;
        r.stage = Stage.DRAFTING;
        r.room = null;
        List<Option> opts = DungeonLogic.draft(pool, r.used, r.roomNumber, totalRooms(), choices(), bossLast(),
                cfgDouble("dungeon.modifier-chance", 0.3), rnd);
        if (opts.isEmpty()) { finishRun(r, true); return; }
        r.draft = new Draft();
        r.draft.options = opts;
        r.draft.deadline = ticks + cfgInt("dungeon.draft-seconds", 20) * 20L;

        Location lobby = lobbyOf(r.lane);
        int slot = 0;
        for (Player p : teamPlayers(r)) {
            GamePlayerUtil.safeTeleport(p, spreadAround(lobby, slot++));
            p.setGameMode(GameMode.ADVENTURE);
            p.sendMessage(api.tr(p.getUniqueId(), "dungeon.draft.chat", r.roomNumber, totalRooms()));
            openDraft(p, r);
        }
    }

    private int[] optionSlots(int n) {
        return switch (n) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{11, 15};
            case 3 -> new int[]{11, 13, 15};
            case 4 -> new int[]{10, 12, 14, 16};
            default -> new int[]{9, 11, 13, 15, 17};
        };
    }

    private Material icon(RoomType t) {
        return switch (t) {
            case COMBAT -> Material.IRON_SWORD;
            case PARKOUR -> Material.LEATHER_BOOTS;
            case TRAP -> Material.TRIPWIRE_HOOK;
            case TREASURE -> Material.CHEST;
            case PUZZLE -> Material.COMPARATOR;
            case BOSS -> Material.WITHER_SKELETON_SKULL;
        };
    }

    private void openDraft(Player p, Run r) {
        DraftHolder h = new DraftHolder(r.team);
        h.inv = Bukkit.createInventory(h, 27, api.tr(p.getUniqueId(), "dungeon.draft.title"));
        renderDraft(h, p, r);
        p.openInventory(h.inv);
    }

    private void renderDraft(DraftHolder h, Player p, Run r) {
        UUID id = p.getUniqueId();
        Inventory inv = h.inv;
        inv.clear();
        Draft d = r.draft;
        if (d == null) return;
        long left = Math.max(0, d.deadline - ticks) / 20;
        inv.setItem(4, named(Material.NETHER_STAR, api.tr(id, "dungeon.draft.header", r.roomNumber, totalRooms(), left),
                api.tr(id, "dungeon.draft.health", (int) r.health.current(), (int) r.health.max(), r.health.revives()),
                api.tr(id, "dungeon.draft.currency", r.currency)));
        int[] slots = optionSlots(d.options.size());
        for (int i = 0; i < d.options.size() && i < slots.length; i++) {
            Option o = d.options.get(i);
            RoomDef def = o.room();
            int votes = 0;
            for (int v : d.votes.values()) if (v == i) votes++;
            boolean mine = d.votes.getOrDefault(id, -1) == i;
            List<String> lore = new ArrayList<>();
            lore.add("§7" + api.tr(id, "dungeon.type." + def.type().name().toLowerCase(Locale.ROOT)));
            lore.add("§7" + api.tr(id, "dungeon.difficulty", "★".repeat(def.difficulty()) + "§8" + "★".repeat(3 - def.difficulty())));
            lore.add("§7" + api.tr(id, "dungeon.reward", DungeonLogic.roomReward(def.reward(), o.modifier())));
            lore.add("§7" + api.tr(id, "dungeon.risk", def.risk()));
            if (o.modifier() != Modifier.NONE)
                lore.add("§d✦ " + api.tr(id, "dungeon.mod." + o.modifier().name().toLowerCase(Locale.ROOT)));
            if (!def.description().isBlank()) lore.add("§8" + def.description());
            lore.add("§e" + api.tr(id, "dungeon.votes", votes) + (mine ? " §a✔" : ""));
            inv.setItem(slots[i], named(icon(def.type()), "§e§l" + def.name(), lore.toArray(new String[0])));
        }
        inv.setItem(21, named(Material.GOLDEN_APPLE, "§a" + api.tr(id, "dungeon.shop.heal"),
                "§7" + api.tr(id, "dungeon.shop.cost", cfgInt("shop.heal-cost", 30)),
                "§7+" + cfgInt("shop.heal-amount", 30) + " HP"));
        inv.setItem(23, named(Material.TOTEM_OF_UNDYING, "§a" + api.tr(id, "dungeon.shop.revive"),
                "§7" + api.tr(id, "dungeon.shop.cost", cfgInt("shop.revive-cost", 60))));
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onDraftClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof DraftHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        UUID id = p.getUniqueId();
        Run r = runs.get(h.team);
        if (r == null || r.draft == null || r.draft.locked || r.stage != Stage.DRAFTING || !h.team.equals(teamId(id))) return;
        int raw = e.getRawSlot();
        if (raw == 21) {                                         // team heal
            int cost = cfgInt("shop.heal-cost", 30);
            if (r.currency < cost) { actionBar(p, api.tr(id, "dungeon.shop.poor")); return; }
            r.currency -= cost;
            r.health.heal(cfgInt("shop.heal-amount", 30));
            for (Player m : teamPlayers(r)) m.sendMessage(api.tr(m.getUniqueId(), "dungeon.shop.healed", p.getName()));
        } else if (raw == 23) {                                  // an extra revive
            int cost = cfgInt("shop.revive-cost", 60);
            if (r.currency < cost) { actionBar(p, api.tr(id, "dungeon.shop.poor")); return; }
            r.currency -= cost;
            r.health.addRevive();
            for (Player m : teamPlayers(r)) m.sendMessage(api.tr(m.getUniqueId(), "dungeon.shop.revived", p.getName()));
        } else {
            int[] slots = optionSlots(r.draft.options.size());
            for (int i = 0; i < slots.length && i < r.draft.options.size(); i++) {
                if (slots[i] != raw) continue;
                if (leaderMode() && !id.equals(leader(r))) { actionBar(p, api.tr(id, "dungeon.draft.leader-only")); return; }
                r.draft.votes.put(id, i);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
                break;
            }
        }
        for (Player m : teamPlayers(r)) {
            if (m.getOpenInventory().getTopInventory().getHolder() instanceof DraftHolder dh) renderDraft(dh, m, r);
        }
    }

    @EventHandler
    public void onDraftDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof DraftHolder) e.setCancelled(true);
    }

    private boolean leaderMode() { return "LEADER".equalsIgnoreCase(gamePlugin.getConfig().getString("dungeon.vote-mode", "MAJORITY")); }

    private UUID leader(Run r) {
        List<UUID> m = new ArrayList<>(membersOf(r.team));
        Collections.sort(m);
        return m.isEmpty() ? null : m.get(0);
    }

    /** Locks the choice (once) and enters the room. */
    private void resolveDraft(Run r, boolean timedOut) {
        Draft d = r.draft;
        if (d == null || d.locked) return;
        int idx = DungeonLogic.resolveVote(d.votes, leaderMode(), leader(r), d.options.size(), rnd);
        Option chosen = idx >= 0 ? d.options.get(idx)
                : DungeonLogic.fallback(d.options, gamePlugin.getConfig().getString("dungeon.fallback", "RANDOM"), rnd);
        if (chosen == null) { finishRun(r, true); return; }
        d.locked = true;
        for (Player p : teamPlayers(r)) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof DraftHolder) p.closeInventory();
            if (idx < 0 && timedOut) p.sendMessage(api.tr(p.getUniqueId(), "dungeon.draft.auto", chosen.room().name()));
        }
        enterRoom(r, chosen);
    }

    // ── Rooms ─────────────────────────────────────────────────────────────────

    private Location abs(RoomRun room, int[] rel) {
        return room.origin.clone().add(rel[0] + 0.5, rel[1], rel[2] + 0.5);
    }

    private Location entryOf(RoomRun room) { return abs(room, room.def.entry()); }

    private void enterRoom(Run r, Option opt) {
        RoomDef def = opt.room();
        RoomRun room = new RoomRun();
        room.def = def;
        room.mod = opt.modifier();
        room.origin = r.lane.clone().add(def.at()[0], def.at()[1], def.at()[2]);
        room.started = ticks;
        int limit = def.timeLimitSeconds();
        if (opt.modifier() == Modifier.REDUCED_TIME) limit = Math.max(20, limit * 3 / 4);
        room.limit = limit > 0 ? limit * 20L : 0;
        r.room = room;
        r.current = opt;
        r.used.add(def.id());
        r.stage = Stage.ROOM;
        r.draft = null;
        r.highest = Math.max(r.highest, def.difficulty());

        int slot = 0;
        for (Player p : teamPlayers(r)) {
            GamePlayerUtil.safeTeleport(p, spreadAround(entryOf(room), slot++));
            p.setGameMode(GameMode.ADVENTURE);
            UUID id = p.getUniqueId();
            p.sendTitle("§e§l" + def.name(), api.tr(id, "dungeon.type." + def.type().name().toLowerCase(Locale.ROOT)), 5, 50, 10);
            p.sendMessage(api.tr(id, "dungeon.room.enter", def.name(),
                    api.tr(id, "dungeon.room.goal." + def.type().name().toLowerCase(Locale.ROOT))));
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1f);
        }
        setupRoom(r, room);
    }

    private void setupRoom(Run r, RoomRun room) {
        Map<String, Object> m = room.def.params();
        switch (room.def.type()) {
            case COMBAT -> {
                var waves = DungeonConfig.waves(m.get("waves"));
                room.totalWaves = waves.size() + (room.mod == Modifier.EXTRA_WAVE ? 1 : 0);
                room.nextWaveAt = ticks + 60;
            }
            case PARKOUR -> { for (Player p : teamPlayers(r)) room.checkpoint.put(p.getUniqueId(), -1); }
            case TRAP -> {
                int i = 0;
                for (int[] s : DungeonConfig.points(m.get("switches"))) {
                    Location l = abs(room, s);
                    blocks.set(l, Material.GOLD_BLOCK);
                    interactive.put(key(l), new String[]{r.team, "switch", String.valueOf(i++)});
                }
                room.total = i;
                if (m.get("hazards") instanceof List<?> hl) {
                    for (Object o : hl) {
                        if (!(o instanceof Map<?, ?> hm)) continue;
                        Hazard h = new Hazard();
                        h.min = DungeonConfig.point(hm.get("min"));
                        h.max = DungeonConfig.point(hm.get("max"));
                        if (h.min == null || h.max == null) continue;
                        h.period = Math.max(2, Math.round(DungeonConfig.dbl(hm.get("period-seconds"), 5) * 20));
                        h.warn = Math.round(DungeonConfig.dbl(hm.get("warn-seconds"), 1.5) * 20);
                        h.damage = DungeonConfig.dbl(hm.get("damage"), 10);
                        room.hazards.add(h);
                    }
                }
            }
            case TREASURE -> {
                int i = 0;
                for (int[] c : DungeonConfig.points(m.get("chests"))) {
                    Location l = abs(room, c);
                    blocks.set(l, Material.CHEST);
                    interactive.put(key(l), new String[]{r.team, "chest", String.valueOf(i++)});
                }
                int req = DungeonConfig.num(m.get("required"), 0);
                room.total = req > 0 ? Math.min(req, i) : i;
            }
            case PUZZLE -> {
                int pads = DungeonConfig.points(m.get("pads")).size();
                room.puzzle = new DungeonLogic.SequencePuzzle(pads, DungeonConfig.num(m.get("length"), 4), rnd);
                Material[] wool = {Material.RED_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
                        Material.PURPLE_WOOL, Material.ORANGE_WOOL};
                int i = 0;
                for (int[] pd : DungeonConfig.points(m.get("pads"))) {
                    Location l = abs(room, pd);
                    blocks.set(l, wool[i % wool.length]);
                    interactive.put(key(l), new String[]{r.team, "pad", String.valueOf(i)});
                    i++;
                }
                startShowing(room, 40);
            }
            case BOSS -> spawnBoss(r, room);
        }
    }

    private void startShowing(RoomRun room, int delay) {
        room.showIndex = -1;
        room.nextStep = ticks + delay;
        room.showUntil = ticks + delay + (long) room.puzzle.sequence().length * 16 + 10;
    }

    // ── Room tick ─────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        for (Run r : runs.values()) {
            if (r.stage == Stage.DRAFTING && r.draft != null && !r.draft.locked) {
                boolean everyone = !leaderMode() && !teamPlayers(r).isEmpty()
                        && r.draft.votes.size() >= teamPlayers(r).size() && ticks % 20 == 0;
                if (ticks >= r.draft.deadline) resolveDraft(r, true);
                else if (everyone) resolveDraft(r, false);
                else if (ticks % 20 == 0) {
                    for (Player p : teamPlayers(r))
                        if (p.getOpenInventory().getTopInventory().getHolder() instanceof DraftHolder dh) renderDraft(dh, p, r);
                }
            } else if (r.stage == Stage.ROOM && r.room != null) {
                tickRoom(r, r.room);
            }
        }
        long left = Math.max(0, durationTicks - ticks) / 20;
        bar("§5§lDUNGEON DRAFT §8| §e" + mmss(left), 1.0 - (double) ticks / Math.max(1, durationTicks),
                left <= 30 ? BarColor.RED : BarColor.PURPLE);
        boolean anyLeft = false;
        for (Run r : runs.values()) if (r.stage == Stage.DRAFTING || r.stage == Stage.ROOM) anyLeft = true;
        if (ticks >= durationTicks || !anyLeft) end();
    }

    private void tickRoom(Run r, RoomRun room) {
        if (room.complete) return;
        if (room.limit > 0 && ticks - room.started >= room.limit) { timeoutRoom(r, room); return; }
        switch (room.def.type()) {
            case COMBAT -> tickCombat(r, room);
            case PARKOUR -> tickParkour(r, room);
            case TRAP -> tickTrap(r, room);
            case TREASURE -> { /* event driven: chest clicks */ }
            case PUZZLE -> tickPuzzle(r, room);
            case BOSS -> tickBoss(r, room);
        }
    }

    // combat ------------------------------------------------------------------

    private void tickCombat(Run r, RoomRun room) {
        room.mobs.removeIf(id -> {
            var e = Bukkit.getEntity(id);
            boolean dead = e == null || !e.isValid() || e.isDead();
            if (dead) room.kills++;
            return dead;
        });
        var waves = DungeonConfig.waves(room.def.params().get("waves"));
        if (room.pending.isEmpty() && room.mobs.isEmpty()) {
            if (room.wave + 1 >= room.totalWaves) {
                if (room.wave >= 0) { completeRoom(r, room); return; }
            }
            if (ticks >= room.nextWaveAt && room.wave + 1 < room.totalWaves) {
                room.wave++;
                Map<String, Integer> w = room.wave < waves.size() ? waves.get(room.wave) : waves.get(waves.size() - 1);
                for (var en : w.entrySet()) {
                    EntityType t = EntityType.valueOf(en.getKey());
                    int n = en.getValue() + (room.wave >= waves.size() ? 2 : 0);
                    for (int i = 0; i < n; i++) room.pending.add(t);
                }
                for (Player p : teamPlayers(r))
                    actionBar(p, api.tr(p.getUniqueId(), "dungeon.combat.wave", room.wave + 1, room.totalWaves));
                room.nextWaveAt = ticks + DungeonConfig.num(room.def.params().get("wave-delay-seconds"), 4) * 20L;
            }
        }
        int maxActive = Math.max(1, DungeonConfig.num(room.def.params().get("max-active"), 8));
        List<int[]> spawns = DungeonConfig.points(room.def.params().get("spawns"));
        if (!room.pending.isEmpty() && room.mobs.size() < maxActive && ticks - room.lastSpawn >= 8 && !spawns.isEmpty()) {
            room.lastSpawn = ticks;
            EntityType t = room.pending.poll();
            Location at = abs(room, spawns.get(rnd.nextInt(spawns.size())));
            spawnMob(r, room, t, at, DungeonConfig.dbl(room.def.params().get("health-scale"), 1.0));
        }
    }

    private LivingEntity spawnMob(Run r, RoomRun room, EntityType type, Location at, double healthScale) {
        World w = at.getWorld();
        if (w == null) return null;
        var e = w.spawnEntity(at, type);
        if (!(e instanceof LivingEntity le)) { e.remove(); return null; }
        double scale = healthScale * (1 + 0.25 * (room.def.difficulty() - 1));
        var attr = le.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(Math.max(1, attr.getBaseValue() * scale));
            le.setHealth(attr.getBaseValue());
        }
        le.setRemoveWhenFarAway(false);
        le.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        le.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, r.team + ":" + room.def.id());
        room.mobs.add(le.getUniqueId());
        if (paused() && le instanceof Mob m) m.setAI(false);
        return le;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(mobKey, PersistentDataType.STRING)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    // parkour -----------------------------------------------------------------

    private void tickParkour(Run r, RoomRun room) {
        Map<String, Object> m = room.def.params();
        int[] fmin = DungeonConfig.point(m.get("finish-min")), fmax = DungeonConfig.point(m.get("finish-max"));
        List<int[]> cps = DungeonConfig.points(m.get("checkpoints"));
        double fallY = room.origin.getY() + DungeonConfig.dbl(m.get("fall-y"), -3);
        int required = Math.max(1, DungeonConfig.num(m.get("required"), 2));
        List<Player> team = teamPlayers(r);
        for (Player p : team) {
            if (!isActive(p.getUniqueId())) continue;
            Location l = p.getLocation();
            UUID id = p.getUniqueId();
            if (room.finished.contains(id)) continue;
            // finish
            if (fmin != null && fmax != null) {
                double x0 = room.origin.getX() + Math.min(fmin[0], fmax[0]), x1 = room.origin.getX() + Math.max(fmin[0], fmax[0]) + 1;
                double y0 = room.origin.getY() + Math.min(fmin[1], fmax[1]), y1 = room.origin.getY() + Math.max(fmin[1], fmax[1]) + 2;
                double z0 = room.origin.getZ() + Math.min(fmin[2], fmax[2]), z1 = room.origin.getZ() + Math.max(fmin[2], fmax[2]) + 1;
                if (l.getX() >= x0 && l.getX() <= x1 && l.getY() >= y0 - 1 && l.getY() <= y1 && l.getZ() >= z0 && l.getZ() <= z1) {
                    room.finished.add(id);
                    p.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                    for (Player t : team) actionBar(t, api.tr(t.getUniqueId(), "dungeon.parkour.finished", room.finished.size(), Math.min(required, team.size())));
                    continue;
                }
            }
            // checkpoints
            int cur = room.checkpoint.getOrDefault(id, -1);
            for (int i = cur + 1; i < cps.size(); i++) {
                Location c = abs(room, cps.get(i));
                if (c.getWorld() == l.getWorld() && c.distanceSquared(l) <= 6.25) { room.checkpoint.put(id, i); break; }
            }
            // a fall: back to the last checkpoint (or the entry) at the price of some team health
            if (l.getY() < fallY) {
                int c = room.checkpoint.getOrDefault(id, -1);
                Location back = c >= 0 ? abs(room, cps.get(c)) : entryOf(room);
                GamePlayerUtil.safeTeleport(p, back);
                p.setFallDistance(0);
                if (shared()) damageTeam(r, DungeonConfig.dbl(m.get("fall-damage"), 6));
                actionBar(p, api.tr(id, "dungeon.parkour.fell"));
            }
        }
        if (room.finished.size() >= Math.min(required, Math.max(1, team.size()))) completeRoom(r, room);
    }

    // trap --------------------------------------------------------------------

    private void tickTrap(Run r, RoomRun room) {
        World w = room.origin.getWorld();
        for (Hazard h : room.hazards) {
            long cycle = (ticks - room.started) % h.period;
            double x0 = room.origin.getX() + Math.min(h.min[0], h.max[0]), x1 = room.origin.getX() + Math.max(h.min[0], h.max[0]) + 1;
            double y0 = room.origin.getY() + Math.min(h.min[1], h.max[1]), y1 = room.origin.getY() + Math.max(h.min[1], h.max[1]) + 2;
            double z0 = room.origin.getZ() + Math.min(h.min[2], h.max[2]), z1 = room.origin.getZ() + Math.max(h.min[2], h.max[2]) + 1;
            if (cycle >= h.period - h.warn && ticks % 6 == 0) {
                w.spawnParticle(Particle.DUST, new Location(w, (x0 + x1) / 2, y0 + 0.3, (z0 + z1) / 2),
                        Math.min(40, (int) ((x1 - x0) * (z1 - z0))), (x1 - x0) / 2, 0.1, (z1 - z0) / 2, 0,
                        new Particle.DustOptions(Color.RED, 1.6f));
            }
            if (cycle < STEP) {                                          // the strike
                w.spawnParticle(Particle.EXPLOSION, new Location(w, (x0 + x1) / 2, y0 + 0.5, (z0 + z1) / 2), 3,
                        (x1 - x0) / 3, 0.2, (z1 - z0) / 3, 0);
                for (Player p : teamPlayers(r)) {
                    Location l = p.getLocation();
                    if (!isActive(p.getUniqueId())) continue;
                    if (l.getX() >= x0 && l.getX() <= x1 && l.getY() >= y0 - 1 && l.getY() <= y1 && l.getZ() >= z0 && l.getZ() <= z1) {
                        p.playSound(l, Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
                        if (shared()) damageTeam(r, h.damage);
                    }
                }
            }
        }
        if (room.total > 0 && room.pressed.size() >= room.total) completeRoom(r, room);
    }

    // puzzle ------------------------------------------------------------------

    private void tickPuzzle(Run r, RoomRun room) {
        if (room.puzzle == null || ticks > room.showUntil) return;
        if (ticks >= room.nextStep) {
            int[] seq = room.puzzle.sequence();
            room.showIndex++;
            room.nextStep = ticks + 16;
            if (room.showIndex < seq.length) {
                List<int[]> pads = DungeonConfig.points(room.def.params().get("pads"));
                int pad = seq[room.showIndex];
                if (pad < pads.size()) {
                    Location l = abs(room, pads.get(pad)).add(0, 1.2, 0);
                    l.getWorld().spawnParticle(Particle.END_ROD, l, 12, 0.2, 0.3, 0.2, 0.02);
                    l.getWorld().playSound(l, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.7f + pad * 0.2f);
                }
            }
        }
    }

    // boss --------------------------------------------------------------------

    private void spawnBoss(Run r, RoomRun room) {
        Map<String, Object> m = room.def.params();
        EntityType t = EntityType.valueOf(DungeonConfig.str(m.get("mob"), "WITHER_SKELETON").toUpperCase(Locale.ROOT));
        int[] sp = DungeonConfig.point(m.get("spawn"));
        LivingEntity boss = spawnMob(r, room, t, abs(room, sp), 1.0);
        if (boss == null) return;
        var attr = boss.getAttribute(Attribute.MAX_HEALTH);
        double hp = DungeonConfig.dbl(m.get("health"), 120) * (1 + 0.15 * (room.def.difficulty() - 1));
        if (attr != null) { attr.setBaseValue(hp); boss.setHealth(hp); }
        boss.setCustomName("§c§l" + room.def.name());
        boss.setCustomNameVisible(true);
        room.mobs.remove(boss.getUniqueId());          // tracked separately
        room.boss = boss;
        room.bar = Bukkit.createBossBar("§c§l" + room.def.name(), BarColor.RED, BarStyle.SEGMENTED_10);
        for (Player p : teamPlayers(r)) room.bar.addPlayer(p);
        room.nextAttack = ticks + Math.round(DungeonConfig.dbl(m.get("attack-interval-seconds"), 6) * 20) + 60;
    }

    private void tickBoss(Run r, RoomRun room) {
        LivingEntity b = room.boss;
        if (b == null || !b.isValid() || b.isDead()) { completeRoom(r, room); return; }
        Map<String, Object> m = room.def.params();
        var attr = b.getAttribute(Attribute.MAX_HEALTH);
        double max = attr != null ? attr.getBaseValue() : 100;
        if (room.bar != null) room.bar.setProgress(Math.max(0, Math.min(1, b.getHealth() / max)));

        // phase two: reinforcements once
        if (!room.addsSummoned && b.getHealth() <= max * DungeonConfig.dbl(m.get("add-at-percent"), 50) / 100.0) {
            room.addsSummoned = true;
            if (m.get("adds") instanceof Map<?, ?> adds) {
                for (var en : adds.entrySet()) {
                    EntityType t = EntityType.valueOf(String.valueOf(en.getKey()).toUpperCase(Locale.ROOT));
                    int n = en.getValue() instanceof Number num ? num.intValue() : 1;
                    for (int i = 0; i < n; i++) spawnMob(r, room, t, b.getLocation().add(rnd.nextInt(5) - 2, 0, rnd.nextInt(5) - 2), 1.0);
                }
            }
            for (Player p : teamPlayers(r)) p.sendMessage(api.tr(p.getUniqueId(), "dungeon.boss.phase2"));
        }
        double radius = DungeonConfig.dbl(m.get("radius"), 4);
        // the telegraphed strike lands
        if (room.strikeAtLoc != null) {
            World w = room.strikeAtLoc.getWorld();
            for (int i = 0; i < 16; i++) {
                double a = 2 * Math.PI * i / 16;
                w.spawnParticle(Particle.DUST, room.strikeAtLoc.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1,
                        0, 0, 0, 0, new Particle.DustOptions(Color.RED, 1.5f));
            }
            if (ticks >= room.strikeAt) {
                w.spawnParticle(Particle.EXPLOSION, room.strikeAtLoc, 4, radius / 2, 0.3, radius / 2, 0);
                w.playSound(room.strikeAtLoc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                for (Player p : teamPlayers(r)) {
                    if (!isActive(p.getUniqueId()) || p.getWorld() != w) continue;
                    if (p.getLocation().distanceSquared(room.strikeAtLoc) <= radius * radius) {
                        if (shared()) damageTeam(r, DungeonConfig.dbl(m.get("damage"), 15));
                        else p.damage(DungeonConfig.dbl(m.get("damage"), 15) / 3.0);
                    }
                }
                room.strikeAtLoc = null;
                double interval = DungeonConfig.dbl(m.get("attack-interval-seconds"), 6) * (room.addsSummoned ? 0.7 : 1.0);
                room.nextAttack = ticks + Math.round(interval * 20);
            }
        } else if (ticks >= room.nextAttack) {
            List<Player> targets = new ArrayList<>();
            for (Player p : teamPlayers(r)) if (isActive(p.getUniqueId())) targets.add(p);
            if (!targets.isEmpty()) {
                Player t = targets.get(rnd.nextInt(targets.size()));
                room.strikeAtLoc = t.getLocation().clone();
                room.strikeAt = ticks + Math.round(DungeonConfig.dbl(m.get("telegraph-seconds"), 1.5) * 20);
                for (Player p : teamPlayers(r)) actionBar(p, api.tr(p.getUniqueId(), "dungeon.boss.telegraph"));
            } else {
                room.nextAttack = ticks + 40;
            }
        }
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (!live || !getState().isRunning() || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!isParticipant(id)) return;
        var b = e.getClickedBlock();
        String[] t = interactive.get(key(b.getX(), b.getY(), b.getZ()));
        if (t == null) return;
        e.setCancelled(true);
        if (!isActive(id)) return;
        if (!t[0].equals(teamId(id))) { actionBar(p, api.tr(id, "dungeon.foreign")); return; }
        Run r = runs.get(t[0]);
        if (r == null || r.stage != Stage.ROOM || r.room == null || r.room.complete) return;
        RoomRun room = r.room;
        int index = Integer.parseInt(t[2]);
        long k = key(b.getX(), b.getY(), b.getZ());
        switch (t[1]) {
            case "switch" -> {
                if (!room.pressed.add(k)) return;
                p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 1.2f);
                b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.2, 0.5), 10, 0.3, 0.3, 0.3, 0);
                for (Player m : teamPlayers(r))
                    actionBar(m, api.tr(m.getUniqueId(), "dungeon.trap.switch", room.pressed.size(), room.total));
            }
            case "chest" -> {
                if (!room.pressed.add(k)) { actionBar(p, api.tr(id, "dungeon.treasure.empty")); return; }   // one claim per chest
                Map<String, Object> m = room.def.params();
                List<Integer> trapped = new ArrayList<>();
                if (m.get("trapped") instanceof List<?> tl) for (Object o : tl) if (o instanceof Number n) trapped.add(n.intValue());
                if (trapped.contains(index)) {
                    p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
                    if (shared()) damageTeam(r, DungeonConfig.dbl(m.get("trap-damage"), 12));
                    for (Player mm : teamPlayers(r)) mm.sendMessage(api.tr(mm.getUniqueId(), "dungeon.treasure.trapped", p.getName()));
                } else {
                    int coins = DungeonConfig.num(m.get("coins"), 15);
                    r.currency += coins;
                    r.chests++;
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
                    for (Player mm : teamPlayers(r)) mm.sendMessage(api.tr(mm.getUniqueId(), "dungeon.treasure.found", p.getName(), coins));
                }
                if (room.pressed.size() >= room.total) completeRoom(r, room);
            }
            case "pad" -> {
                if (room.puzzle == null || ticks <= room.showUntil) { actionBar(p, api.tr(id, "dungeon.puzzle.watch")); return; }
                var res = room.puzzle.press(index);
                Location l = b.getLocation().add(0.5, 1.3, 0.5);
                if (res == Press.OK) {
                    p.playSound(l, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.7f + index * 0.2f);
                    l.getWorld().spawnParticle(Particle.END_ROD, l, 6, 0.2, 0.2, 0.2, 0.01);
                } else if (res == Press.WRONG) {
                    p.playSound(l, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
                    double dmg = DungeonConfig.dbl(room.def.params().get("mistake-damage"), 6);
                    if (shared()) damageTeam(r, dmg);
                    for (Player m : teamPlayers(r)) actionBar(m, api.tr(m.getUniqueId(), "dungeon.puzzle.wrong"));
                    startShowing(room, 40);
                } else if (res == Press.DONE) {
                    completeRoom(r, room);
                }
            }
            default -> { }
        }
    }

    // ── Shared health ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDamage(EntityDamageEvent e) {
        if (!shared() || !live || !(e.getEntity() instanceof Player p)) return;
        Run r = runs.get(teamId(p.getUniqueId()));
        if (r == null || !isParticipant(p.getUniqueId())) return;
        e.setCancelled(true);                     // players are never hurt directly: the team pool takes it
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {     // cancelled void damage must not leave a player falling forever
            GamePlayerUtil.safeTeleport(p, respawnLocation(p));
            p.setFallDistance(0);
            if (r.stage == Stage.ROOM) damageTeam(r, cfgDouble("health.void-damage", 5));
            return;
        }
        if (r.stage != Stage.ROOM || r.room == null) return;
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent be && be.getDamager() instanceof Player) return;
        damageTeam(r, e.getFinalDamage() * cfgDouble("health.damage-factor", 2.5));
    }

    private void damageTeam(Run r, double amount) {
        if (r.stage != Stage.ROOM) return;
        HealthResult res = r.health.damage(amount);
        for (Player p : teamPlayers(r)) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.6f, 1f);
            if (res == HealthResult.REVIVED) {
                p.sendTitle(api.tr(p.getUniqueId(), "dungeon.revive.title"), api.tr(p.getUniqueId(), "dungeon.revive.sub"), 5, 40, 10);
            }
        }
        if (res == HealthResult.REVIVED) r.revivesUsed++;
        if (res == HealthResult.FAILED) failRun(r);
    }

    @Override
    protected void onPlayerDown(Player victim, UUID killer) {
        Run r = runs.get(teamId(victim.getUniqueId()));
        if (r != null) r.deaths++;
    }

    // ── Completion ────────────────────────────────────────────────────────────

    private void completeRoom(Run r, RoomRun room) {
        if (room.complete) return;
        room.complete = true;
        RoomDef def = room.def;
        long used = ticks - room.started;
        int reward = DungeonLogic.roomReward(def.reward(), room.mod);
        int bonus = 0;
        if (room.limit > 0 && DungeonLogic.timeLeftFraction(room.limit, used) >= 0.5)
            bonus = Math.round(reward * cfgInt("points.fast-bonus-percent", 15) / 100f);
        if (!shared()) reward = Math.max(0, reward - reward * cfgInt("points.death-penalty-percent", 10) * Math.min(5, countDeathsThisRoom(r)) / 100);
        if (def.type() == RoomType.BOSS) { r.bosses++; bonus += cfgInt("points.boss-bonus", 100); }

        String key = "dd:" + r.team + ":" + r.roomNumber + ":" + def.id();
        List<UUID> crew = membersOf(r.team);
        awardShared(key + ":room", r.team, crew, reward, PointAward.Reason.OBJECTIVE, "room");
        awardShared(key + ":bonus", r.team, crew, bonus, PointAward.Reason.BONUS, "bonus");
        reportObjective(null, r.team, def.type() == RoomType.BOSS ? nl.kmc.core.event.GameObjectiveEvent.Type.BOSS_KILLED
                : nl.kmc.core.event.GameObjectiveEvent.Type.ROOM_CLEARED);
        r.points += reward;
        r.bonus += bonus;
        r.roomsDone++;
        r.currency += Math.max(0, reward / 10);
        if (room.mod == Modifier.HEAL) r.health.heal(r.health.max() * 0.2);
        cleanupRoom(r, room);

        for (Player p : teamPlayers(r)) {
            UUID id = p.getUniqueId();
            p.sendMessage(api.tr(id, "dungeon.room.done", def.name(), reward, bonus));
            p.sendTitle("§a§l✔ " + def.name(), "§e+" + (reward + bonus), 0, 40, 10);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        if (r.roomNumber >= totalRooms()) finishRun(r, true);
        else Bukkit.getScheduler().runTaskLater(gamePlugin, () -> {
            if (getState().isRunning() && r.stage == Stage.ROOM && r.room == null) startDraft(r);
        }, 60L);
    }

    private int countDeathsThisRoom(Run r) { return r.deaths; }

    private void timeoutRoom(Run r, RoomRun room) {
        if (room.complete) return;
        room.complete = true;
        cleanupRoom(r, room);
        for (Player p : teamPlayers(r)) {
            p.sendMessage(api.tr(p.getUniqueId(), "dungeon.room.timeout", room.def.name()));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_HURT, 0.6f, 1.2f);
        }
        if (shared()) damageTeam(r, cfgDouble("health.timeout-damage", 15));    // may fail the run
        if (r.stage == Stage.FAILED) return;
        if (r.roomNumber >= totalRooms()) finishRun(r, false);                  // the last room is not cleared
        else Bukkit.getScheduler().runTaskLater(gamePlugin, () -> {
            if (getState().isRunning() && r.stage == Stage.ROOM && r.room == null) startDraft(r);
        }, 40L);
    }

    private void failRun(Run r) {
        if (r.stage == Stage.FAILED || r.stage == Stage.COMPLETED) return;
        if (r.room != null) { r.room.complete = true; cleanupRoom(r, r.room); }
        finishRun(r, false);
    }

    private void finishRun(Run r, boolean completed) {
        r.stage = completed ? Stage.COMPLETED : Stage.FAILED;
        r.finishedAt = ticks;
        r.draft = null;
        if (completed && r.roomsDone >= totalRooms()) {
            int bonus = cfgInt("points.run-complete", 100);
            int hp = (int) Math.round(r.health.fraction() * cfgInt("points.health-bonus", 50));
            awardShared("dd:" + r.team + ":run", r.team, membersOf(r.team), bonus + hp, PointAward.Reason.BONUS, "run");
            r.bonus += bonus + hp;
        }
        Location lobby = lobbyOf(r.lane);
        int slot = 0;
        for (Player p : teamPlayers(r)) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof DraftHolder) p.closeInventory();
            GamePlayerUtil.safeTeleport(p, spreadAround(lobby, slot++));
            p.setGameMode(GameMode.ADVENTURE);
            p.sendTitle(api.tr(p.getUniqueId(), completed ? "dungeon.end.done" : "dungeon.end.failed"),
                    api.tr(p.getUniqueId(), "dungeon.end.sub", r.roomsDone), 5, 60, 15);
        }
    }

    private void cleanupRoom(Run r, RoomRun room) {
        for (UUID id : new ArrayList<>(room.mobs)) {
            var e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        room.mobs.clear();
        room.pending.clear();
        if (room.boss != null && room.boss.isValid()) room.boss.remove();
        if (room.bar != null) { room.bar.removeAll(); room.bar = null; }
        interactive.values().removeIf(v -> v[0].equals(r.team));
        r.room = null;
        // the markers of this room are restored with the rest at the end of the game; the clicks are dead already
    }

    // ── HUD / result ──────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        Run r = runs.get(teamId(id));
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "dungeon.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (r != null) {
            l.add(api.tr(id, "dungeon.sb.room", Math.min(r.roomNumber, totalRooms()), totalRooms()));
            if (r.stage == Stage.ROOM && r.room != null) {
                l.add("§e" + r.room.def.name());
                l.add(objective(id, r));
            } else if (r.stage == Stage.DRAFTING) {
                l.add(api.tr(id, "dungeon.sb.drafting"));
            } else {
                l.add(api.tr(id, r.stage == Stage.COMPLETED ? "dungeon.sb.completed" : "dungeon.sb.failed"));
            }
            l.add(api.tr(id, "dungeon.sb.health", (int) r.health.current(), (int) r.health.max(), r.health.revives()));
            l.add(api.tr(id, "dungeon.sb.currency", r.currency));
            l.add("");
            l.add(api.tr(id, "dungeon.sb.team", shownTeamPoints(r.team)));
        }
        return l;
    }

    private String objective(UUID id, Run r) {
        RoomRun room = r.room;
        return switch (room.def.type()) {
            case COMBAT -> api.tr(id, "dungeon.sb.combat", Math.max(0, room.wave + 1), room.totalWaves, room.mobs.size() + room.pending.size());
            case PARKOUR -> api.tr(id, "dungeon.sb.parkour", room.finished.size());
            case TRAP -> api.tr(id, "dungeon.sb.switches", room.pressed.size(), room.total);
            case TREASURE -> api.tr(id, "dungeon.sb.chests", room.pressed.size(), room.total);
            case PUZZLE -> api.tr(id, "dungeon.sb.puzzle", room.puzzle == null ? 0 : room.puzzle.progress(), room.puzzle == null ? 0 : room.puzzle.sequence().length);
            case BOSS -> api.tr(id, "dungeon.sb.boss", room.boss != null && room.boss.isValid() ? (int) room.boss.getHealth() : 0);
        };
    }

    @Override
    protected Result finishGame() {
        for (Run r : runs.values()) {
            if (r.stage == Stage.ROOM || r.stage == Stage.DRAFTING) {      // time ran out: no reward for an unfinished room
                if (r.room != null) { r.room.complete = true; cleanupRoom(r, r.room); }
                finishRun(r, false);
            }
        }
        List<String> ranked = book.rankTeams(runs.keySet());
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) order.addAll(membersOf(t));
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "dungeon.results"));
            int i = 1;
            for (String t : ranked) {
                Run r = runs.get(t);
                if (r == null) continue;
                long secs = Math.max(0, (r.finishedAt > 0 ? r.finishedAt : ticks) - r.start) / 20;
                v.sendMessage(api.tr(vid, "dungeon.result-line", i++, teamName(t), shownTeamPoints(t), r.roomsDone,
                        r.highest, r.bosses, r.chests, (int) r.health.current(), r.revivesUsed, secs));
            }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        UUID mvp = order.isEmpty() ? null : order.get(0);
        return new Result(winner, mvp, mvp != null ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        if (pauseWatch != null) { pauseWatch.cancel(); pauseWatch = null; }
        for (Run r : runs.values()) if (r.room != null) cleanupRoom(r, r.room);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof DraftHolder) p.closeInventory();
        }
        // any tracked mob that is still around (a crash-safe sweep by tag)
        for (World w : Bukkit.getWorlds()) {
            for (var e : w.getLivingEntities()) {
                if (e.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING)) e.remove();
            }
        }
        blocks.restoreAll();
        interactive.clear();
        runs.clear();
    }
}
