package nl.kmc.crafting;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.crafting.OrderLogic.Difficulty;
import nl.kmc.crafting.OrderLogic.Line;
import nl.kmc.crafting.OrderLogic.PileStock;
import nl.kmc.crafting.OrderLogic.PoolItem;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Crafting Countdown (Build Mart style, with recipe crafting instead of building): every team gets orders — items to craft — and collects the
 * items from resource piles spread over the arena. A pile is a block you right-click: a menu shows what it
 * holds, each pick gives a configurable amount, piles have a stock, a per-player cooldown and restock by
 * themselves. Everything is carried to the team's own delivery station; progress is shared by the team.
 * A completed order pays points (items, speed, streak) to the players who delivered it, and the next order
 * is generated straight away, a little harder every few orders. Crafting is the vanilla crafting table (every
 * player has a portable workbench item); there is no building.
 */
public final class CraftingCountdownManager extends ArcadeGameManager {

    // ── Data ──────────────────────────────────────────────────────────────────

    private record PileType(String id, String name, Material icon, List<Material> items, int perTake, int stock, int restock) {}

    private static final class Pile {
        final String id;
        final PileType type;
        final Location at;
        final Map<Material, PileStock> stock = new LinkedHashMap<>();
        TextDisplay label;
        boolean dirty = true;
        Pile(String id, PileType type, Location at) { this.id = id; this.type = type; this.at = at; }
    }

    /** Marks the portable workbench item. */
    private NamespacedKey workbenchKey;
    /** Marks the mining tools (they are not carried goods). */
    private NamespacedKey toolKey;
    /** What each player mines at each pile: "<player>:<pile>" to the item. */
    private final Map<String, Material> mining = new HashMap<>();

    private static final class Order {
        final int seq;
        final List<Line> lines;
        final int[] delivered;
        long issued;
        final int itemPts;
        final Map<UUID, Integer> contrib = new LinkedHashMap<>();
        Order(int seq, List<Line> lines, long issued, int itemPts) {
            this.seq = seq; this.lines = lines; this.delivered = new int[lines.size()];
            this.issued = issued; this.itemPts = itemPts;
        }
        boolean complete() {
            for (int i = 0; i < lines.size(); i++) if (delivered[i] < lines.get(i).required()) return false;
            return true;
        }
        int totalRequired() { int n = 0; for (Line l : lines) n += l.required(); return n; }
    }

    private static final class TeamState {
        int station;
        int sharedIndex;                       // >0 when another team uses the same station block
        final List<Order> orders = new ArrayList<>();
        int issued;                            // orders generated so far (also the order number)
        int completed;
        int streak;
        long lastCompleteTick = Long.MIN_VALUE / 2;
        int items;                             // items delivered in total
        int speedPts, streakPts;
        TextDisplay board;
        boolean dirty = true;
    }

    /** Holder of a pile menu, so clicks in it are recognised without looking at titles. */
    private static final class PileHolder implements InventoryHolder {
        final Pile pile;
        final Map<Integer, Material> slots = new HashMap<>();
        Inventory inv;
        PileHolder(Pile pile) { this.pile = pile; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final CraftingCountdownPlugin plugin;
    private final Map<String, PileType> types = new LinkedHashMap<>();
    private final List<Pile> piles = new ArrayList<>();
    private final Map<Long, Pile> pileAt = new HashMap<>();
    private final Map<Long, Integer> stationAt = new HashMap<>();
    private List<Location> stations = new ArrayList<>();
    private final Map<String, TeamState> teams = new HashMap<>();
    private final Map<UUID, Integer> delivered = new HashMap<>();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Random rnd = new Random();

    private List<PoolItem> pool = new ArrayList<>();
    private final Map<String, PoolItem> poolById = new HashMap<>();
    private List<Difficulty> difficulty = new ArrayList<>();
    private final Set<String> obtainable = new HashSet<>();

    private long durationTicks;
    private long nextRestock;
    private boolean finalAnnounced;
    private int lastBeep = -1;

    public CraftingCountdownManager(CraftingCountdownPlugin plugin, GameRegistration reg, StatisticsService stats,
                                    ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private int cfgInt(String path, int def) { return gamePlugin.getConfig().getInt(path, def); }
    private double cfgDouble(String path, double def) { return gamePlugin.getConfig().getDouble(path, def); }

    /** Pile types from {@code pile-types}; invalid items are reported in {@code problems}. */
    private Map<String, PileType> loadTypes(List<String> problems) {
        Map<String, PileType> out = new LinkedHashMap<>();
        ConfigurationSection s = gamePlugin.getConfig().getConfigurationSection("pile-types");
        if (s == null) return out;
        for (String id : s.getKeys(false)) {
            ConfigurationSection t = s.getConfigurationSection(id);
            if (t == null) continue;
            List<Material> items = new ArrayList<>();
            for (String name : t.getStringList("items")) {
                Material m = Material.matchMaterial(name);
                if (m == null || m.isAir() || !m.isItem()) {
                    if (problems != null) problems.add("pile-types." + id + ": onbekend item " + name);
                    continue;
                }
                if (!items.contains(m)) items.add(m);
            }
            if (items.isEmpty()) { if (problems != null) problems.add("pile-types." + id + ": geen geldige items"); continue; }
            Material icon = Material.matchMaterial(t.getString("icon", items.get(0).name()));
            if (icon == null) icon = items.get(0);
            out.put(id, new PileType(id, t.getString("name", OrderLogic.pretty(id)), icon, items,
                    Math.max(1, t.getInt("per-take", cfgInt("piles.per-take", 8))),
                    Math.max(1, t.getInt("stock", cfgInt("piles.stock", 64))),
                    Math.max(0, t.getInt("restock-amount", cfgInt("piles.restock-amount", 16)))));
        }
        return out;
    }

    private List<PoolItem> loadPool(List<String> problems) {
        List<PoolItem> out = new ArrayList<>();
        for (Object o : gamePlugin.getConfig().getList("item-pool", List.of())) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String item = String.valueOf(m.get("item")).toUpperCase(Locale.ROOT);
            Material mat = Material.matchMaterial(item);
            if (mat == null || !mat.isItem()) { if (problems != null) problems.add("item-pool: onbekend item " + item); continue; }
            int tier = m.get("tier") instanceof Number n ? n.intValue() : 1;
            int min = m.get("min") instanceof Number n ? n.intValue() : 4;
            int max = m.get("max") instanceof Number n ? n.intValue() : min;
            double value = m.get("value") instanceof Number n ? n.doubleValue() : 1.0;
            // the raw materials this item is crafted from; they have to be on the piles
            Set<String> requires = new LinkedHashSet<>();
            if (m.get("requires") instanceof List<?> rl) {
                for (Object r : rl) {
                    Material rm = Material.matchMaterial(String.valueOf(r));
                    if (rm == null || !rm.isItem()) {
                        if (problems != null) problems.add("item-pool " + item + ": onbekende grondstof " + r);
                        continue;
                    }
                    requires.add(rm.name());
                }
            }
            out.add(new PoolItem(mat.name(), Math.max(1, tier), Math.max(1, min), Math.max(1, max), value, requires));
        }
        return out;
    }

    private List<Difficulty> loadDifficulty() {
        List<Difficulty> out = new ArrayList<>();
        for (Object o : gamePlugin.getConfig().getList("orders.difficulty", List.of())) {
            if (!(o instanceof Map<?, ?> m)) continue;
            out.add(new Difficulty(m.get("from-order") instanceof Number n ? n.intValue() : 0,
                    Math.max(1, m.get("lines") instanceof Number n ? n.intValue() : 2),
                    Math.max(1, m.get("max-tier") instanceof Number n ? n.intValue() : 1),
                    m.get("scale") instanceof Number n ? n.doubleValue() : 1.0));
        }
        return out;
    }

    /** Where the piles stand, per type, from {@code arena.piles.<type>}. */
    private Map<String, List<Location>> loadPileLocations() {
        Map<String, List<Location>> out = new LinkedHashMap<>();
        ConfigurationSection s = gamePlugin.getConfig().getConfigurationSection("arena.piles");
        if (s == null) return out;
        for (String type : s.getKeys(false)) out.put(type, arena.locs("piles." + type));
        return out;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§6§lCRAFTING COUNTDOWN"; }
    @Override protected String introSubKey() { return "@crafting.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@crafting.tut.1", "@crafting.tut.2", "@crafting.tut.3", "@crafting.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/craftcountdown world)");
        if (arena.locs("stations").isEmpty()) p.add("geen inleverpunten (/craftcountdown station add)");
        Map<String, PileType> t = loadTypes(p);
        Set<String> items = new HashSet<>();
        int placed = 0;
        for (var en : loadPileLocations().entrySet()) {
            PileType type = t.get(en.getKey());
            if (type == null) { if (!en.getValue().isEmpty()) p.add("stapels van onbekend type '" + en.getKey() + "'"); continue; }
            placed += en.getValue().size();
            if (!en.getValue().isEmpty()) for (Material m : type.items()) items.add(m.name());
        }
        if (placed == 0) p.add("geen stapels (/craftcountdown pile add <type>)");
        List<PoolItem> pl = loadPool(p);
        if (pl.isEmpty()) p.add("lege item-pool in config.yml");
        else if (placed > 0 && OrderLogic.candidates(pl, items, 1).isEmpty())
            p.add("geen enkel recept uit de item-pool (tier 1) is te maken met de grondstoffen van de geplaatste stapels");
        if (loadDifficulty().isEmpty()) p.add("orders.difficulty is leeg");
        return p;
    }

    @Override
    protected List<Location> spawnPoints() {
        List<Location> s = arena.locs("spawns");
        return s.isEmpty() ? arena.locs("stations") : s;
    }

    @Override
    protected Location flyoverCenter() {
        List<Location> all = new ArrayList<>(arena.locs("stations"));
        for (var l : loadPileLocations().values()) all.addAll(l);
        if (all.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location l : all) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        return new Location(all.get(0).getWorld(), x / all.size(), y / all.size() + 6, z / all.size());
    }

    // ── Preparation ───────────────────────────────────────────────────────────

    @Override
    protected void prepareGame() {
        teams.clear();
        delivered.clear();
        cooldowns.clear();
        piles.clear();
        pileAt.clear();
        stationAt.clear();
        obtainable.clear();
        types.clear();
        types.putAll(loadTypes(null));
        pool = loadPool(null);
        poolById.clear();
        for (PoolItem p : pool) poolById.put(p.item(), p);
        difficulty = loadDifficulty();
        stations = arena.locs("stations");
        durationTicks = cfgInt("game.duration-seconds", 240) * 20L;
        finalAnnounced = false;
        lastBeep = -1;
        workbenchKey = new NamespacedKey(gamePlugin, "workbench");
        toolKey = new NamespacedKey(gamePlugin, "tool");
        mining.clear();

        // piles (a pile is a block: right-click it)
        int n = 0;
        for (var en : loadPileLocations().entrySet()) {
            PileType type = types.get(en.getKey());
            if (type == null) continue;
            for (Location at : en.getValue()) {
                Pile pile = new Pile(type.id() + "#" + (++n), type, at);
                for (Material m : type.items()) pile.stock.put(m, new PileStock(type.stock()));
                piles.add(pile);
                pileAt.put(key(at.getBlockX(), at.getBlockY(), at.getBlockZ()), pile);
                for (Material m : type.items()) obtainable.add(m.name());
                spawnLabel(pile);
            }
        }
        for (int i = 0; i < stations.size(); i++) {
            Location s = stations.get(i);
            stationAt.put(key(s.getBlockX(), s.getBlockY(), s.getBlockZ()), i);
        }

        // teams: one station each (stations are shared, with a warning, when there are fewer than teams)
        List<String> ids = activeTeams();
        Map<Integer, Integer> perStation = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            TeamState ts = new TeamState();
            ts.station = i % stations.size();
            ts.sharedIndex = perStation.merge(ts.station, 1, Integer::sum) - 1;
            teams.put(ids.get(i), ts);
        }
        if (ids.size() > stations.size())
            log.warning("[CraftingCountdown] " + ids.size() + " teams but only " + stations.size()
                    + " delivery stations — teams share stations.");
        for (var en : teams.entrySet()) {
            TeamState ts = en.getValue();
            int parallel = Math.max(1, cfgInt("orders.active-per-team", 1));
            for (int i = 0; i < parallel; i++) issueOrder(en.getKey(), ts);
            spawnBoard(en.getKey(), ts);
        }
        // everybody stands around their own station
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            TeamState ts = teams.get(teamId(id));
            if (p == null || ts == null) continue;
            int slot = Math.max(0, membersOf(teamId(id)).indexOf(id)) + ts.sharedIndex * 4;
            GamePlayerUtil.safeTeleport(p, spreadAround(stations.get(ts.station), slot));
        }
    }

    @Override
    protected void beginPlay() {
        nextRestock = ticks + restockTicks();
        for (var en : teams.entrySet()) {
            TeamState ts = en.getValue();
            // the clock of an order starts now, not when it was drawn during the preparation
            for (Order o : ts.orders) o.issued = ticks;
            ts.dirty = true;
            for (UUID m : membersOf(en.getKey())) {
                Player p = Bukkit.getPlayer(m);
                if (p == null) continue;
                p.sendTitle(api.tr(m, "crafting.go.title"), api.tr(m, "crafting.go.sub"), 5, 50, 10);
                for (Order o : ts.orders) announceOrder(m, o, false);
            }
        }
        for (Player p : activePlayers()) {
            p.setGameMode(GameMode.SURVIVAL);         // digging needs survival; everything else stays blocked
            giveTools(p);
            giveWorkbench(p);
        }
    }

    // ── Portable workbench ────────────────────────────────────────────────────

    private boolean isWorkbench(ItemStack it) {
        if (it == null || it.getType() != Material.CRAFTING_TABLE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(workbenchKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Everybody can craft anywhere: right-click this item to open a crafting table. */
    private void giveWorkbench(Player p) {
        if (!gamePlugin.getConfig().getBoolean("crafting.workbench-item", true)) return;
        for (ItemStack it : p.getInventory().getContents()) if (isWorkbench(it)) return;
        ItemStack bench = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta meta = bench.getItemMeta();
        meta.setDisplayName(api.tr(p.getUniqueId(), "crafting.workbench"));
        meta.getPersistentDataContainer().set(workbenchKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        bench.setItemMeta(meta);
        // slot 9 when it is free (and the tools never sit there), else the first free hotbar slot
        var inv = p.getInventory();
        int slot = 8;
        if (inv.getItem(slot) != null && !isTool(inv.getItem(slot))) {
            slot = -1;
            for (int i = 8; i >= 0; i--) if (inv.getItem(i) == null) { slot = i; break; }
        }
        if (slot < 0) {                                  // a full hotbar: the workbench wins slot 9
            ItemStack old = inv.getItem(8);
            inv.setItem(8, bench);
            if (old != null) inv.addItem(old);
        } else inv.setItem(slot, bench);
        p.updateInventory();
    }

    private boolean isTool(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(toolKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** An unbreakable pickaxe and axe: the piles are real blocks that have to be mined. */
    private void giveTools(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isTool(it)) return;
        for (int n = 0; n < 2; n++) {
            ItemStack tool = new ItemStack(n == 0 ? Material.IRON_PICKAXE : Material.IRON_AXE);
            ItemMeta m = tool.getItemMeta();
            m.setDisplayName(api.tr(p.getUniqueId(), n == 0 ? "crafting.tool.pickaxe" : "crafting.tool.axe"));
            m.setUnbreakable(true);
            m.addEnchant(org.bukkit.enchantments.Enchantment.EFFICIENCY, 3, true);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE,
                    org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            m.getPersistentDataContainer().set(toolKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            tool.setItemMeta(m);
            p.getInventory().setItem(n, tool);
        }
    }

    private long restockTicks() { return Math.max(1, cfgInt("piles.restock-seconds", 15)) * 20L; }

    // ── Orders ────────────────────────────────────────────────────────────────

    private Order issueOrder(String team, TeamState ts) {
        Difficulty d = OrderLogic.difficultyFor(difficulty, ts.completed);
        int maxQty = Math.max(1, cfgInt("orders.max-quantity-per-line", 48));
        List<Line> lines = OrderLogic.generate(pool, obtainable, d, maxQty, rnd);
        if (lines.isEmpty()) {            // cannot happen after validation; never leave a team without an order
            PoolItem any = pool.isEmpty() ? null : pool.get(0);
            lines = List.of(new Line(any != null ? any.item() : Material.COBBLESTONE.name(), 8));
        }
        int itemPts = OrderLogic.itemPoints(lines, poolById, cfgDouble("points.per-value", 1.0));
        Order o = new Order(++ts.issued, lines, ticks, itemPts);
        ts.orders.add(o);
        ts.dirty = true;
        return o;
    }

    private void announceOrder(UUID viewer, Order o, boolean fresh) {
        Player p = Bukkit.getPlayer(viewer);
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < o.lines.size(); i++) {
            if (i > 0) sb.append("§7, ");
            sb.append("§f").append(o.lines.get(i).required()).append("x §e")
                    .append(OrderLogic.pretty(o.lines.get(i).item()));
        }
        p.sendMessage(api.tr(viewer, fresh ? "crafting.order.new" : "crafting.order.first", o.seq, sb.toString()));
        String needs = ingredients(o);
        if (!needs.isEmpty()) p.sendMessage(api.tr(viewer, "crafting.order.needs", needs));
    }

    /** The raw materials an order is made from, for the hint under the order. */
    private String ingredients(Order o) {
        Set<String> all = new LinkedHashSet<>();
        for (Line l : o.lines) {
            PoolItem pi = poolById.get(l.item());
            if (pi != null) for (String r : pi.requires()) all.add(OrderLogic.pretty(r));
        }
        return String.join("§7, §f", all);
    }

    // ── Piles ─────────────────────────────────────────────────────────────────

    private void spawnLabel(Pile pile) {
        World w = pile.at.getWorld();
        if (w == null) return;
        pile.label = w.spawn(pile.at.clone().add(0.5, 1.9, 0.5), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
        });
        updateLabel(pile);
    }

    private void updateLabel(Pile pile) {
        pile.dirty = false;
        if (pile.label == null || !pile.label.isValid()) return;
        Component c = Component.text(pile.type.name(), NamedTextColor.GOLD, TextDecoration.BOLD);
        for (var en : pile.stock.entrySet()) {
            PileStock st = en.getValue();
            boolean has = st.amount() > 0;
            c = c.append(Component.newline())
                    .append(Component.translatable(en.getKey().translationKey(), has ? NamedTextColor.GRAY : NamedTextColor.DARK_RED))
                    .append(Component.text(" " + st.amount() + "/" + st.max(), has ? NamedTextColor.WHITE : NamedTextColor.RED));
        }
        c = c.append(Component.newline()).append(Component.text("▸ rechtsklik", NamedTextColor.YELLOW));
        pile.label.text(c);
    }

    private void restock() {
        for (Pile pile : piles) {
            if (pile.type.restock() <= 0) continue;
            for (PileStock st : pile.stock.values()) {
                if (st.amount() < st.max()) { st.add(pile.type.restock()); pile.dirty = true; }
            }
        }
    }

    /** Items on you, not counting the workbench item. */
    private int carried(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && !isWorkbench(it) && !isTool(it)) n += it.getAmount();
        return n;
    }

    private void openPile(Player p, Pile pile) {
        PileHolder h = new PileHolder(pile);
        int rows = Math.max(1, (pile.stock.size() + 8) / 9);
        h.inv = Bukkit.createInventory(h, rows * 9, "§6" + pile.type.name());
        render(h, p);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.4f);
    }

    private void render(PileHolder h, Player p) {
        h.inv.clear();
        h.slots.clear();
        int slot = 0;
        int carry = carried(p), max = cfgInt("piles.max-carry", 128);
        for (var en : h.pile.stock.entrySet()) {
            Material m = en.getKey();
            PileStock st = en.getValue();
            ItemStack it = new ItemStack(m, Math.max(1, Math.min(st.amount(), 64)));
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName("§e§l" + OrderLogic.pretty(m.name()));
            meta.setLore(List.of(
                    "§7Voorraad: §f" + st.amount() + "§8/§7" + st.max(),
                    "§7Per klik: §f" + Math.min(h.pile.type.perTake(), Math.max(0, st.amount())),
                    "§7Je draagt: §f" + carry + "§8/§7" + max,
                    st.amount() > 0 ? "§aKlik om te pakken" : "§cLeeg — komt terug"));
            it.setItemMeta(meta);
            h.inv.setItem(slot, it);
            h.slots.put(slot, m);
            slot++;
        }
    }

    /** One pick from a pile: validated entirely on the server (stock, cooldown, carry limit). */
    private void take(Player p, Pile pile, Material m) {
        UUID id = p.getUniqueId();
        if (!live || !getState().isRunning() || !isActive(id)) return;
        PileStock st = pile.stock.get(m);
        if (st == null) return;

        String ck = id + ":" + pile.id;
        if (!cooldowns.ready(ck, ticks)) {
            actionBar(p, api.tr(id, "crafting.pile.cooldown", Math.max(1, (int) Math.ceil(cooldowns.remaining(ck, ticks) / 20.0))));
            return;
        }
        int max = cfgInt("piles.max-carry", 128);
        int room = OrderLogic.carryRoom(carried(p), max);
        if (room <= 0) {
            actionBar(p, api.tr(id, "crafting.pile.full", max));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        if (st.amount() <= 0) {
            actionBar(p, api.tr(id, "crafting.pile.empty", OrderLogic.pretty(m.name())));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        int got = st.take(Math.min(pile.type.perTake(), room));
        var left = p.getInventory().addItem(new ItemStack(m, got));
        int back = 0;
        for (ItemStack rest : left.values()) back += rest.getAmount();
        if (back > 0) { st.add(back); got -= back; }
        if (got <= 0) return;
        long cd = Math.round(Math.max(0.0, cfgDouble("piles.cooldown-seconds", 2.0)) * 20);
        if (cd > 0) cooldowns.start(ck, ticks, cd);
        pile.dirty = true;
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
        actionBar(p, api.tr(id, "crafting.pile.took", got, OrderLogic.pretty(m.name()), carried(p), max));
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    private int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == m) n += it.getAmount();
        return n;
    }

    private void remove(Player p, Material m, int amount) {
        int left = amount;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType() != m) continue;
            int n = Math.min(left, it.getAmount());
            if (n == it.getAmount()) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - n);
            left -= n;
        }
    }

    /** Hands in everything the team's open orders still need; completes orders and starts the next ones. */
    private void submit(Player p, String team, TeamState ts) {
        UUID id = p.getUniqueId();
        int total = 0;
        List<Order> done = new ArrayList<>();
        for (Order o : ts.orders) {
            for (int i = 0; i < o.lines.size(); i++) {
                Line l = o.lines.get(i);
                int need = l.required() - o.delivered[i];
                if (need <= 0) continue;
                Material m = Material.matchMaterial(l.item());
                if (m == null) continue;
                int give = Math.min(need, count(p, m));
                if (give <= 0) continue;
                remove(p, m, give);
                o.delivered[i] += give;
                o.contrib.merge(id, give, Integer::sum);
                ts.items += give;
                total += give;
                delivered.merge(id, give, Integer::sum);
            }
            if (o.complete()) done.add(o);
        }
        if (total == 0) {
            actionBar(p, api.tr(id, "crafting.submit.nothing"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        ts.dirty = true;
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        actionBar(p, api.tr(id, "crafting.submit.ok", total));
        for (Order o : done) completeOrder(team, ts, o);
    }

    private void completeOrder(String team, TeamState ts, Order o) {
        int base = Math.max(0, cfgInt("points.base-per-order", 10));
        long parTicks = Math.round(o.totalRequired() * Math.max(0.1, cfgDouble("points.par-seconds-per-item", 3.0)) * 20);
        long elapsed = Math.max(0, ticks - o.issued);
        int speed = OrderLogic.speedBonus(elapsed, parTicks, Math.max(0, cfgInt("points.speed-bonus", 25)));
        long window = Math.max(1, cfgInt("points.streak-window-seconds", 90)) * 20L;
        ts.streak = ticks - ts.lastCompleteTick <= window ? ts.streak + 1 : 1;
        ts.lastCompleteTick = ticks;
        int streakPts = OrderLogic.streakBonus(ts.streak, Math.max(0, cfgInt("points.streak-per-step", 5)),
                Math.max(0, cfgInt("points.streak-cap", 25)));

        int core = base + o.itemPts;
        pay(team, o, "core", core, PointAward.Reason.OBJECTIVE, "order");
        pay(team, o, "speed", speed, PointAward.Reason.BONUS, "speed");
        pay(team, o, "streak", streakPts, PointAward.Reason.BONUS, "streak");
        ts.speedPts += speed;
        ts.streakPts += streakPts;
        ts.completed++;

        int total = core + speed + streakPts;
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            if (!isParticipant(vid)) continue;
            if (team.equals(teamId(vid))) {
                v.sendMessage(api.tr(vid, "crafting.order.done", o.seq, base, o.itemPts, speed, streakPts, total));
                v.sendTitle("§a§l✔ #" + o.seq, "§e+" + total, 0, 40, 10);
                v.playSound(v.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else {
                v.sendMessage(api.tr(vid, "crafting.order.other", teamName(team), o.seq, total));
            }
        }
        ts.orders.remove(o);
        Order next = issueOrder(team, ts);
        for (UUID m : membersOf(team)) announceOrder(m, next, true);
    }

    /** Pays one part of an order to the players who delivered for it, in proportion to their items. */
    private void pay(String team, Order o, String part, int points, PointAward.Reason reason, String category) {
        if (points <= 0) return;
        Map<UUID, Integer> shares = OrderLogic.split(points, o.contrib);
        if (shares.isEmpty()) { awardTeam("o:" + team + ":" + o.seq + ":" + part, team, points, reason, category); return; }
        for (var en : shares.entrySet())
            award("o:" + team + ":" + o.seq + ":" + part + ":" + en.getKey(), en.getKey(), en.getValue(), reason, category);
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                && live && getState().isRunning() && isParticipant(id) && isWorkbench(e.getItem())) {
            e.setCancelled(true);
            if (isActive(id)) p.openWorkbench(null, true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!live || !getState().isRunning() || e.getClickedBlock() == null) return;
        if (!isParticipant(id)) return;
        var b = e.getClickedBlock();
        long k = key(b.getX(), b.getY(), b.getZ());

        Pile pile = pileAt.get(k);
        if (pile != null) {
            e.setCancelled(true);
            if (isActive(id)) openPile(p, pile);
            return;
        }
        Integer st = stationAt.get(k);
        if (st == null) return;
        e.setCancelled(true);
        String team = teamId(id);
        TeamState ts = teams.get(team);
        if (ts == null || ts.station != st) {
            // somebody else's delivery station: say whose it is
            String owner = null;
            for (var en : teams.entrySet()) if (en.getValue().station == st) { owner = en.getKey(); break; }
            actionBar(p, api.tr(id, "crafting.submit.foreign", owner != null ? teamName(owner) : "?"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        if (isActive(id)) submit(p, team, ts);
    }

    @EventHandler
    public void onPileClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof PileHolder h)) return;
        e.setCancelled(true);                       // nothing can be moved in or out of the menu
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Material m = h.slots.get(e.getRawSlot());
        if (m == null) return;
        // choosing is not taking: from now on the blocks of this pile give this item when they are mined
        mining.put(p.getUniqueId() + ":" + h.pile.id, m);
        p.closeInventory();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
        actionBar(p, api.tr(p.getUniqueId(), "crafting.pile.mining", OrderLogic.pretty(m.name())));
    }

    /** Mining is real: the pile block is dug with the pickaxe or axe, and every break gives the chosen item. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onMine(org.bukkit.event.block.BlockBreakEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!live || !getState().isRunning() || !isParticipant(id)) return;
        var b = e.getBlock();
        Pile pile = pileAt.get(key(b.getX(), b.getY(), b.getZ()));
        if (pile == null) return;
        e.setCancelled(true);                        // the block stays: it is a pile, not a block you can remove
        e.setDropItems(false);
        if (!isActive(id)) return;
        Material sel = mining.get(id + ":" + pile.id);
        if (sel == null || !pile.stock.containsKey(sel)) {
            sel = null;
            for (var en : pile.stock.entrySet()) if (en.getValue().amount() > 0) { sel = en.getKey(); break; }
            if (sel == null && !pile.stock.isEmpty()) sel = pile.stock.keySet().iterator().next();
            if (sel == null) return;
            mining.put(id + ":" + pile.id, sel);
        }
        take(p, pile, sel);
        if (pile.dirty) updateLabel(pile);
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, 0, b.getBlockData());
    }

    @EventHandler
    public void onPileDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PileHolder) e.setCancelled(true);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        if (ticks >= nextRestock) {
            restock();
            nextRestock = ticks + restockTicks();
        }
        if (ticks % 40 == 0) for (Player p : activePlayers()) { giveTools(p); giveWorkbench(p); }   // lost it in the crafting grid?
        if (ticks % 10 == 0) {
            for (Pile pile : piles) if (pile.dirty) updateLabel(pile);
            for (var en : teams.entrySet()) if (en.getValue().dirty) updateBoard(en.getKey(), en.getValue());
        }

        long left = Math.max(0, durationTicks - ticks);
        int seconds = (int) Math.ceil(left / 20.0);
        int finalSec = Math.max(5, cfgInt("game.final-countdown-seconds", 30));
        if (!finalAnnounced && left <= finalSec * 20L) {
            finalAnnounced = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isParticipant(p.getUniqueId())) continue;
                p.sendTitle(api.tr(p.getUniqueId(), "crafting.final.title"),
                        api.tr(p.getUniqueId(), "crafting.final.sub", finalSec), 5, 50, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.6f);
            }
        }
        if (finalAnnounced && seconds <= 10 && seconds != lastBeep && seconds > 0) {
            lastBeep = seconds;
            for (Player p : Bukkit.getOnlinePlayers())
                if (isParticipant(p.getUniqueId()))
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.8f + (10 - seconds) * 0.08f);
        }
        bar("§6§lCRAFTING COUNTDOWN §8| §e" + mmss(seconds) + (finalAnnounced ? " §8| §c§lFINALE" : ""),
                (double) left / Math.max(1, durationTicks), finalAnnounced ? BarColor.RED : BarColor.GREEN);
        if (ticks >= durationTicks) end();
    }

    // ── Board (hologram at the delivery station) ──────────────────────────────

    private void spawnBoard(String team, TeamState ts) {
        Location st = stations.get(ts.station);
        World w = st.getWorld();
        if (w == null) return;
        ts.board = w.spawn(st.clone().add(0.0, 2.4 + ts.sharedIndex * 1.6, 0.0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(170, 0, 0, 0));
        });
        updateBoard(team, ts);
    }

    private void updateBoard(String team, TeamState ts) {
        ts.dirty = false;
        if (ts.board == null || !ts.board.isValid()) return;
        Component c = Component.text("INLEVERPUNT ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(ChatColor.stripColor(teamName(team)), NamedTextColor.WHITE));
        for (Order o : ts.orders) {
            c = c.append(Component.newline()).append(Component.text("Bestelling #" + o.seq, NamedTextColor.YELLOW));
            for (int i = 0; i < o.lines.size(); i++) {
                Line l = o.lines.get(i);
                int got = Math.min(o.delivered[i], l.required());
                boolean full = got >= l.required();
                Material m = Material.matchMaterial(l.item());
                Component name = m != null ? Component.translatable(m.translationKey()) : Component.text(l.item());
                c = c.append(Component.newline())
                        .append(Component.text(full ? "✔ " : "• ", full ? NamedTextColor.GREEN : NamedTextColor.GRAY))
                        .append(name.color(full ? NamedTextColor.GREEN : NamedTextColor.WHITE))
                        .append(Component.text(" " + got + "/" + l.required(), full ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
        }
        if (!ts.orders.isEmpty()) {
            String needs = ingredients(ts.orders.get(0));
            if (!needs.isEmpty())
                c = c.append(Component.newline())
                        .append(Component.text("Grondstoffen: " + ChatColor.stripColor(needs), NamedTextColor.DARK_GRAY));
        }
        c = c.append(Component.newline()).append(Component.text("Rechtsklik om in te leveren", NamedTextColor.GRAY));
        ts.board.text(c);
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        TeamState ts = teams.get(team);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "crafting.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (ts != null) {
            int shown = 0;
            for (Order o : ts.orders) {
                for (int i = 0; i < o.lines.size() && shown < 4; i++, shown++) {
                    Line li = o.lines.get(i);
                    int got = Math.min(o.delivered[i], li.required());
                    l.add(api.tr(id, got >= li.required() ? "crafting.sb.line-done" : "crafting.sb.line",
                            got, li.required(), OrderLogic.pretty(li.item())));
                }
            }
            l.add("");
            l.add(api.tr(id, "crafting.sb.orders", ts.completed, ts.streak));
        }
        l.add(api.tr(id, "crafting.sb.carry", carried(viewer), cfgInt("piles.max-carry", 128)));
        l.add(api.tr(id, "crafting.sb.you", delivered.getOrDefault(id, 0)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        List<String> ranked = book.rankTeams(teams.keySet());
        awardTeamPlacements(ranked);

        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(delivered.getOrDefault(b, 0), delivered.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "crafting.results"));
            int i = 1;
            for (String t : ranked) {
                TeamState ts = teams.get(t);
                if (ts == null) continue;
                v.sendMessage(api.tr(vid, "crafting.result-line", i++, teamName(t), shownTeamPoints(t),
                        ts.completed, ts.items, ts.speedPts, ts.streakPts));
            }
        }
        UUID mvp = null;
        int best = -1;
        for (UUID id : players) {
            int c = delivered.getOrDefault(id, 0);
            if (c > best) { best = c; mvp = id; }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        return new Result(winner, mvp, mvp != null && best > 0 ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        for (Pile pile : piles) if (pile.label != null && pile.label.isValid()) pile.label.remove();
        for (TeamState ts : teams.values()) if (ts.board != null && ts.board.isValid()) ts.board.remove();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof PileHolder) p.closeInventory();
        }
        piles.clear();
        pileAt.clear();
        stationAt.clear();
        teams.clear();
        delivered.clear();
    }
}
