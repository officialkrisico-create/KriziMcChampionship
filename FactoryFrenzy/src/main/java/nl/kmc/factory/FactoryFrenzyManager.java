package nl.kmc.factory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.factory.FactoryModel.*;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.game.api.arcade.TemporaryBlocks;
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
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Factory Frenzy: every team runs its own factory. Raw materials come from resource stations, machines turn
 * them into products (a state machine with a queue and an output buffer), and products are delivered at the
 * delivery block against the team's open orders. Machines, stations and the delivery point are real blocks
 * placed for the game and restored afterwards; every transaction is validated on the server.
 */
public final class FactoryFrenzyManager extends ArcadeGameManager {

    // ── Config model ──────────────────────────────────────────────────────────

    record ItemDef(String id, String name, Material material) {}
    record StationDef(String id, String item, int[] at, int stock, int restockSeconds, int restockAmount, int take) {}
    record MachineDef(String id, String type, int[] at, String name) {}

    /** Everything read from config.yml, plus the problems found while reading it. */
    static final class Model {
        final Map<String, ItemDef> items = new LinkedHashMap<>();
        final Map<String, MachineType> types = new LinkedHashMap<>();
        final Map<String, Material> typeBlock = new HashMap<>();
        final Map<String, String> typeName = new HashMap<>();
        final List<StationDef> stations = new ArrayList<>();
        final List<MachineDef> machines = new ArrayList<>();
        final List<OrderDef> orders = new ArrayList<>();
        int[] delivery = {3, 0, 3}, board = {0, 0, -5}, spawn = {0, 0, 6};
        final List<String> problems = new ArrayList<>();
    }

    // ── Runtime ───────────────────────────────────────────────────────────────

    private record Target(String team, char kind, String id) {}

    private static final class TeamFactory {
        Location origin;
        final Map<String, Machine> machines = new LinkedHashMap<>();
        final Map<String, Station> stations = new LinkedHashMap<>();
        final Map<String, Long> nextRestock = new HashMap<>();
        final List<Order> open = new ArrayList<>();
        int issued, completed, partialItems, itemsDelivered, rawTaken, points;
        int valueDelivered;
        TextDisplay board;
        boolean dirty = true;
    }

    private static final class MachineHolder implements InventoryHolder {
        final String team, machine;
        Inventory inv;
        MachineHolder(String team, String machine) { this.team = team; this.machine = machine; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final FactoryFrenzyPlugin plugin;
    private Model model = new Model();
    private final Map<String, TeamFactory> factories = new LinkedHashMap<>();
    private final Map<Long, Target> targets = new HashMap<>();
    private final TemporaryBlocks blocks = new TemporaryBlocks();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Integer> delivered = new HashMap<>();
    private final Random rnd = new Random();
    private NamespacedKey itemKey, runKey;
    private long durationTicks;
    private boolean finalAnnounced;

    public FactoryFrenzyManager(FactoryFrenzyPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Config loading and validation ─────────────────────────────────────────

    private int cfgInt(String p, int d) { return gamePlugin.getConfig().getInt(p, d); }
    private double cfgDouble(String p, double d) { return gamePlugin.getConfig().getDouble(p, d); }

    private static Map<String, Integer> amounts(ConfigurationSection s, String key, List<String> problems, String what) {
        Map<String, Integer> out = new LinkedHashMap<>();
        ConfigurationSection c = s == null ? null : s.getConfigurationSection(key);
        if (c == null) return out;
        for (String k : c.getKeys(false)) out.put(k.toLowerCase(Locale.ROOT), Math.max(0, c.getInt(k)));
        return out;
    }

    private static int[] triple(List<Integer> l, int[] def) {
        return l != null && l.size() >= 3 ? new int[]{l.get(0), l.get(1), l.get(2)} : def;
    }

    Model loadModel() { return parse(gamePlugin.getConfig()); }

    /** Reads and validates the whole factory definition (no server needed: tests use this too). */
    static Model parse(org.bukkit.configuration.file.FileConfiguration cfg) {
        Model m = new Model();

        ConfigurationSection items = cfg.getConfigurationSection("items");
        if (items != null) for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            Material mat = Material.matchMaterial(s == null ? "" : s.getString("material", ""));
            if (mat == null || mat.name().startsWith("LEGACY_") || mat == Material.AIR) { m.problems.add("items." + id + ": onbekend materiaal"); continue; }
            m.items.put(id.toLowerCase(Locale.ROOT), new ItemDef(id.toLowerCase(Locale.ROOT), s.getString("name", id), mat));
        }

        ConfigurationSection types = cfg.getConfigurationSection("machine-types");
        if (types != null) for (String id : types.getKeys(false)) {
            ConfigurationSection t = types.getConfigurationSection(id);
            if (t == null) continue;
            List<Recipe> recipes = new ArrayList<>();
            for (Object o : t.getList("recipes", List.of())) {
                if (!(o instanceof Map<?, ?> r)) continue;
                Map<String, Integer> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
                if (r.get("in") instanceof Map<?, ?> im) im.forEach((k, v) -> in.put(String.valueOf(k).toLowerCase(Locale.ROOT), v instanceof Number n ? n.intValue() : 0));
                if (r.get("out") instanceof Map<?, ?> om) om.forEach((k, v) -> out.put(String.valueOf(k).toLowerCase(Locale.ROOT), v instanceof Number n ? n.intValue() : 0));
                int secs = r.get("seconds") instanceof Number n ? n.intValue() : 5;
                recipes.add(new Recipe(String.valueOf(r.get("id")), in, out, Math.max(1, secs * 20)));
            }
            m.types.put(id, new MachineType(id, t.getInt("queue", 3), t.getInt("output-cap", 12), recipes));
            Material block = Material.matchMaterial(t.getString("block", "FURNACE"));
            m.typeBlock.put(id, block != null && !block.name().startsWith("LEGACY_") ? block : Material.FURNACE);
            m.typeName.put(id, t.getString("name", id));
        }

        ConfigurationSection layout = cfg.getConfigurationSection("layout");
        if (layout != null) {
            for (Object o : layout.getList("stations", List.of())) {
                if (!(o instanceof Map<?, ?> s)) continue;
                int[] at = pos(s.get("at"));
                String item = String.valueOf(s.get("item")).toLowerCase(Locale.ROOT);
                if (at == null) { m.problems.add("layout.stations: positie ontbreekt bij " + s.get("id")); continue; }
                m.stations.add(new StationDef(String.valueOf(s.get("id")), item, at,
                        s.get("stock") instanceof Number n ? n.intValue() : 40,
                        s.get("restock-seconds") instanceof Number n ? n.intValue() : 8,
                        s.get("restock-amount") instanceof Number n ? n.intValue() : 6,
                        s.get("take") instanceof Number n ? n.intValue() : 4));
            }
            for (Object o : layout.getList("machines", List.of())) {
                if (!(o instanceof Map<?, ?> s)) continue;
                int[] at = pos(s.get("at"));
                if (at == null) { m.problems.add("layout.machines: positie ontbreekt bij " + s.get("id")); continue; }
                String type = String.valueOf(s.get("type"));
                if (!m.types.containsKey(type)) { m.problems.add("layout.machines " + s.get("id") + ": onbekend machine-type " + type); continue; }
                m.machines.add(new MachineDef(String.valueOf(s.get("id")), type, at, m.typeName.getOrDefault(type, type)));
            }
            m.delivery = triple(layout.getIntegerList("delivery"), m.delivery);
            m.board = triple(layout.getIntegerList("board"), m.board);
            m.spawn = triple(layout.getIntegerList("spawn"), m.spawn);
        }

        for (Object o : cfg.getList("order-pool", List.of())) {
            if (!(o instanceof Map<?, ?> s)) continue;
            FactoryModel.Difficulty d;
            try { d = FactoryModel.Difficulty.valueOf(String.valueOf(s.get("difficulty")).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { d = FactoryModel.Difficulty.SIMPLE; }
            Map<String, Integer> need = new LinkedHashMap<>();
            if (s.get("items") instanceof Map<?, ?> im) im.forEach((k, v) -> need.put(String.valueOf(k).toLowerCase(Locale.ROOT), v instanceof Number n ? n.intValue() : 0));
            m.orders.add(new OrderDef(String.valueOf(s.get("id")), String.valueOf(s.get("name") != null ? s.get("name") : s.get("id")), d, need,
                    s.get("reward") instanceof Number n ? n.intValue() : 100,
                    s.get("deadline-seconds") instanceof Number n ? n.intValue() : 0,
                    s.get("bonus-percent") instanceof Number n ? n.intValue() : 0));
        }

        // validation of the production graph
        if (m.items.isEmpty()) m.problems.add("geen items in config.yml");
        if (m.machines.isEmpty()) m.problems.add("geen machines in layout.machines");
        if (m.stations.isEmpty()) m.problems.add("geen grondstofstations in layout.stations");
        if (m.orders.isEmpty()) m.problems.add("geen orders in order-pool");
        Set<String> raw = new HashSet<>();
        for (StationDef s : m.stations) {
            if (!m.items.containsKey(s.item())) m.problems.add("station " + s.id() + ": onbekend item " + s.item());
            raw.add(s.item());
        }
        Set<String> placed = new HashSet<>();
        for (MachineDef d : m.machines) placed.add(d.type());
        m.problems.addAll(FactoryModel.validate(m.items.keySet(), raw, m.types, placed, m.orders));
        return m;
    }

    private static int[] pos(Object o) {
        if (o instanceof List<?> l && l.size() >= 3 && l.get(0) instanceof Number a && l.get(1) instanceof Number b && l.get(2) instanceof Number c)
            return new int[]{a.intValue(), b.intValue(), c.intValue()};
        return null;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§6§lFACTORY FRENZY"; }
    @Override protected String introSubKey() { return "@factory.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@factory.tut.1", "@factory.tut.2", "@factory.tut.3", "@factory.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/factory world)");
        if (arena.locs("factories").isEmpty()) p.add("geen fabrieken (/factory factory add)");
        p.addAll(loadModel().problems);
        return p;
    }

    @Override
    protected List<Location> spawnPoints() {
        Model m = loadModel();
        List<Location> out = new ArrayList<>();
        for (Location o : arena.locs("factories")) out.add(o.clone().add(m.spawn[0], m.spawn[1], m.spawn[2]));
        return out;
    }

    @Override
    protected Location flyoverCenter() {
        List<Location> f = arena.locs("factories");
        if (f.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location l : f) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        return new Location(f.get(0).getWorld(), x / f.size(), y / f.size() + 12, z / f.size());
    }

    // ── Preparation ───────────────────────────────────────────────────────────

    @Override
    protected void prepareGame() {
        factories.clear();
        targets.clear();
        delivered.clear();
        cooldowns.clear();
        model = loadModel();
        itemKey = new NamespacedKey(gamePlugin, "ff_item");
        runKey = new NamespacedKey(gamePlugin, "ff_run");
        durationTicks = cfgInt("game.duration-seconds", 420) * 20L;
        finalAnnounced = false;

        List<Location> origins = arena.locs("factories");
        List<String> ids = activeTeams();
        if (ids.size() > origins.size()) {
            String msg = "§c[Factory Frenzy] " + ids.size() + " teams maar " + origins.size()
                    + " fabrieken — elk team heeft een eigen fabriek nodig (/factory factory add).";
            for (Player op : Bukkit.getOnlinePlayers()) if (op.isOp() || op.hasPermission("factoryfrenzy.admin")) op.sendMessage(msg);
            log.severe(msg);
            Bukkit.getScheduler().runTask(gamePlugin, this::end);
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            String team = ids.get(i);
            TeamFactory f = new TeamFactory();
            f.origin = origins.get(i);
            build(team, f);
            factories.put(team, f);
        }
    }

    private Location at(TeamFactory f, int[] o) {
        return f.origin.clone().add(o[0], o[1], o[2]);
    }

    private void build(String team, TeamFactory f) {
        for (StationDef s : model.stations) {
            Location l = at(f, s.at());
            ItemDef item = model.items.get(s.item());
            blocks.set(l, item != null && item.material().isBlock() ? item.material() : Material.BARREL);
            f.stations.put(s.id(), new Station(s.id(), s.item(), s.stock()));
            f.nextRestock.put(s.id(), 0L);
            targets.put(key(l), new Target(team, 'S', s.id()));
        }
        for (MachineDef d : model.machines) {
            Location l = at(f, d.at());
            blocks.set(l, model.typeBlock.getOrDefault(d.type(), Material.FURNACE));
            f.machines.put(d.id(), new Machine(d.id(), model.types.get(d.type())));
            targets.put(key(l), new Target(team, 'M', d.id()));
        }
        Location dl = at(f, model.delivery);
        blocks.set(dl, Material.GOLD_BLOCK);
        targets.put(key(dl), new Target(team, 'D', "delivery"));

        Location bl = at(f, model.board).add(0.5, 2.2, 0.5);
        f.board = bl.getWorld().spawn(bl, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(170, 0, 0, 0));
        });
        // labels above stations and machines
        for (StationDef s : model.stations) label(at(f, s.at()), "§e" + itemName(s.item()));
        for (MachineDef d : model.machines) label(at(f, d.at()), "§6" + d.name());
        label(dl, "§a§lLEVERPUNT");
    }

    private final List<TextDisplay> labels = new ArrayList<>();

    private void label(Location l, String text) {
        TextDisplay d = l.getWorld().spawn(l.clone().add(0.5, 1.5, 0.5), TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.text(Component.text(text));
        });
        labels.add(d);
    }

    private static long key(Location l) { return key(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private String itemName(String id) {
        ItemDef d = model.items.get(id);
        return d != null ? d.name() : id;
    }

    @Override
    protected void beginPlay() {
        int open = Math.max(1, cfgInt("orders.open", 2));
        for (var en : factories.entrySet()) {
            for (int i = 0; i < open; i++) issueOrder(en.getKey(), en.getValue());
            for (UUID m : membersOf(en.getKey())) {
                Player p = Bukkit.getPlayer(m);
                if (p == null) continue;
                p.sendTitle(api.tr(m, "factory.go.title"), api.tr(m, "factory.go.sub"), 5, 50, 10);
                p.getInventory().clear();
            }
        }
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    private void issueOrder(String team, TeamFactory f) {
        Set<String> openIds = new HashSet<>();
        for (Order o : f.open) openIds.add(o.def.id());
        OrderDef d = FactoryModel.nextOrder(model.orders, f.completed, cfgInt("orders.medium-after", 2),
                cfgInt("orders.complex-after", 5), openIds, rnd);
        if (d == null) return;
        f.open.add(new Order(d, ticks));
        f.issued++;
        f.dirty = true;
        for (UUID m : membersOf(team)) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(api.tr(m, "factory.order.new", d.name(), describe(d.items()), d.reward()));
        }
    }

    private String describe(Map<String, Integer> items) {
        StringBuilder sb = new StringBuilder();
        for (var e : items.entrySet()) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§f").append(e.getValue()).append("x §e").append(itemName(e.getKey()));
        }
        return sb.toString();
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    private ItemStack makeItem(String id, int amount) {
        ItemDef d = model.items.get(id);
        ItemStack it = new ItemStack(d != null ? d.material() : Material.PAPER, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§b" + (d != null ? d.name() : id));
        meta.setLore(List.of("§8Factory Frenzy"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, runId);
        it.setItemMeta(meta);
        return it;
    }

    /** The factory item id of a stack, or null when it is not one of this game's items. */
    private String itemId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if (!runId.equals(pdc.get(runKey, PersistentDataType.STRING))) return null;
        return pdc.get(itemKey, PersistentDataType.STRING);
    }

    private Map<String, Integer> carriedItems(Player p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            String id = itemId(it);
            if (id != null) out.merge(id, it.getAmount(), Integer::sum);
        }
        return out;
    }

    private int carriedTotal(Player p) {
        int n = 0;
        for (int v : carriedItems(p).values()) n += v;
        return n;
    }

    private void removeItems(Player p, String id, int amount) {
        int left = amount;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && left > 0; i++) {
            if (!id.equals(itemId(c[i]))) continue;
            int n = Math.min(left, c[i].getAmount());
            if (n == c[i].getAmount()) p.getInventory().setItem(i, null);
            else c[i].setAmount(c[i].getAmount() - n);
            left -= n;
        }
    }

    /** Puts items in the inventory; what does not fit is returned. */
    private int give(Player p, String id, int amount) {
        var left = p.getInventory().addItem(makeItem(id, amount));
        int back = 0;
        for (ItemStack r : left.values()) back += r.getAmount();
        return back;
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (!live || !getState().isRunning() || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!isParticipant(id)) return;
        var b = e.getClickedBlock();
        Target t = targets.get(key(b.getX(), b.getY(), b.getZ()));
        if (t == null) return;
        e.setCancelled(true);
        if (!isActive(id)) return;
        String team = teamId(id);
        if (!t.team().equals(team)) {
            actionBar(p, api.tr(id, "factory.foreign", teamName(t.team())));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        TeamFactory f = factories.get(team);
        if (f == null) return;
        switch (t.kind()) {
            case 'S' -> takeFromStation(p, f, t.id());
            case 'M' -> openMachine(p, team, f, t.id());
            case 'D' -> deliver(p, team, f);
            default -> { }
        }
    }

    private void takeFromStation(Player p, TeamFactory f, String stationId) {
        UUID id = p.getUniqueId();
        Station st = f.stations.get(stationId);
        StationDef def = null;
        for (StationDef s : model.stations) if (s.id().equals(stationId)) def = s;
        if (st == null || def == null) return;
        String ck = id + ":" + stationId;
        if (!cooldowns.ready(ck, ticks)) {
            actionBar(p, api.tr(id, "factory.station.cooldown"));
            return;
        }
        int room = Math.max(0, cfgInt("game.max-carry", 48) - carriedTotal(p));
        if (room <= 0) { actionBar(p, api.tr(id, "factory.station.full", cfgInt("game.max-carry", 48))); return; }
        if (st.stock() <= 0) {
            actionBar(p, api.tr(id, "factory.station.empty", itemName(def.item())));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        int got = st.take(Math.min(def.take(), room));
        int back = give(p, def.item(), got);
        if (back > 0) { st.giveBack(back); got -= back; }
        if (got <= 0) return;
        f.rawTaken += got;
        cooldowns.start(ck, ticks, Math.round(cfgDouble("game.station-cooldown-seconds", 1.0) * 20));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
        actionBar(p, api.tr(id, "factory.station.took", got, itemName(def.item()), st.stock(), st.max()));
    }

    // ── Machine menu ──────────────────────────────────────────────────────────

    private void openMachine(Player p, String team, TeamFactory f, String machineId) {
        Machine m = f.machines.get(machineId);
        if (m == null) return;
        MachineHolder h = new MachineHolder(team, machineId);
        h.inv = Bukkit.createInventory(h, 27, "§6" + model.typeName.getOrDefault(m.type.id(), m.type.id()));
        renderMachine(h, p, m);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1.2f);
    }

    private Material stateIcon(MachineState s) {
        return switch (s) {
            case PROCESSING -> Material.LIME_CONCRETE;
            case READY -> Material.LIGHT_BLUE_CONCRETE;
            case BLOCKED -> Material.RED_CONCRETE;
            case WAITING_FOR_INPUT -> Material.YELLOW_CONCRETE;
            case DISABLED -> Material.BLACK_CONCRETE;
            default -> Material.GRAY_CONCRETE;
        };
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        it.setItemMeta(meta);
        return it;
    }

    private void renderMachine(MachineHolder h, Player p, Machine m) {
        UUID id = p.getUniqueId();
        Inventory inv = h.inv;
        inv.clear();
        MachineState s = m.state();
        List<String> status = new ArrayList<>();
        status.add("§7" + api.tr(id, "factory.state." + s.name().toLowerCase(Locale.ROOT)));
        if (m.currentRecipe() != null) {
            int total = m.currentRecipe().ticks();
            int done = total - m.ticksLeft();
            int filled = Math.max(0, Math.min(10, done * 10 / Math.max(1, total)));
            status.add("§a" + "█".repeat(filled) + "§8" + "█".repeat(10 - filled) + " §f" + Math.max(0, m.ticksLeft() / 20) + "s");
        }
        status.add("§7" + api.tr(id, "factory.queue", m.queued(), m.type.queueSize()));
        inv.setItem(4, named(stateIcon(s), "§6§l" + model.typeName.getOrDefault(m.type.id(), m.type.id()), status.toArray(new String[0])));

        int slot = 10;
        for (Recipe r : m.type.recipes()) {
            if (slot > 12) break;
            String out = r.out().keySet().iterator().next();
            ItemDef od = model.items.get(out);
            inv.setItem(slot++, named(od != null ? od.material() : Material.PAPER, "§e" + itemName(out),
                    "§7" + api.tr(id, "factory.recipe.in", describe(r.in())),
                    "§7" + api.tr(id, "factory.recipe.out", describe(r.out())),
                    "§7" + api.tr(id, "factory.recipe.time", r.ticks() / 20)));
        }
        List<String> buf = new ArrayList<>();
        for (var e : m.input().entrySet()) buf.add("§f" + e.getValue() + "x §e" + itemName(e.getKey()));
        if (buf.isEmpty()) buf.add("§8" + api.tr(id, "factory.empty"));
        inv.setItem(13, named(Material.PAPER, "§b" + api.tr(id, "factory.buffer"), buf.toArray(new String[0])));
        inv.setItem(14, named(Material.HOPPER, "§a§l" + api.tr(id, "factory.insert.label"), "§7" + api.tr(id, "factory.insert.lore")));
        List<String> out = new ArrayList<>();
        for (var e : m.output().entrySet()) out.add("§f" + e.getValue() + "x §e" + itemName(e.getKey()));
        if (out.isEmpty()) out.add("§8" + api.tr(id, "factory.empty"));
        inv.setItem(15, named(Material.CHEST, "§a§l" + api.tr(id, "factory.collect"), out.toArray(new String[0])));
        inv.setItem(16, named(Material.DROPPER, "§c" + api.tr(id, "factory.return.label"), "§7" + api.tr(id, "factory.return.lore")));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MachineHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!live || !isActive(p.getUniqueId()) || !h.team.equals(teamId(p.getUniqueId()))) return;
        TeamFactory f = factories.get(h.team);
        Machine m = f == null ? null : f.machines.get(h.machine);
        if (m == null) return;
        UUID id = p.getUniqueId();
        switch (e.getRawSlot()) {
            case 14 -> {                                    // put every usable item in
                int total = 0;
                for (var en : carriedItems(p).entrySet()) {
                    if (!m.accepts(en.getKey())) continue;
                    int in = m.insert(en.getKey(), en.getValue());
                    if (in > 0) { removeItems(p, en.getKey(), in); total += in; }
                }
                actionBar(p, api.tr(id, total > 0 ? "factory.inserted" : "factory.nothing-to-insert", total));
                if (total > 0) p.playSound(p.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.5f, 1.6f);
            }
            case 15 -> {                                    // take the output
                Map<String, Integer> out = m.collect();
                int n = 0;
                for (var en : out.entrySet()) {
                    int back = give(p, en.getKey(), en.getValue());
                    n += en.getValue() - back;
                    if (back > 0) m.restoreOutput(en.getKey(), back);     // no room: it stays in the machine
                }
                if (n > 0) { actionBar(p, api.tr(id, "factory.collected", n)); p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f); }
            }
            case 16 -> {                                    // unprocessed input back
                int n = 0;
                for (var en : m.returnInputs().entrySet()) {
                    int back = give(p, en.getKey(), en.getValue());
                    n += en.getValue() - back;
                    if (back > 0) m.insert(en.getKey(), back);
                }
                if (n > 0) actionBar(p, api.tr(id, "factory.returned", n));
            }
            default -> { }
        }
        renderMachine(h, p, m);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MachineHolder) e.setCancelled(true);
    }

    // ── Delivery and scoring ──────────────────────────────────────────────────

    private void deliver(Player p, String team, TeamFactory f) {
        UUID id = p.getUniqueId();
        Map<String, Integer> have = carriedItems(p);
        int total = 0;
        for (var en : have.entrySet()) {
            int left = en.getValue();
            for (Order o : f.open) {
                if (left <= 0) break;
                if (o.done() || !o.needs(en.getKey())) continue;
                int took = o.deliver(en.getKey(), left, id);
                if (took > 0) {
                    left -= took;
                    total += took;
                    removeItems(p, en.getKey(), took);
                    f.itemsDelivered += took;
                    delivered.merge(id, took, Integer::sum);
                }
            }
        }
        if (total == 0) {
            actionBar(p, api.tr(id, "factory.deliver.nothing"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        f.dirty = true;
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        actionBar(p, api.tr(id, "factory.deliver.ok", total));
        List<Order> finished = new ArrayList<>();
        for (Order o : f.open) if (o.done()) finished.add(o);
        for (Order o : finished) completeOrder(team, f, o);
    }

    private void completeOrder(String team, TeamFactory f, Order o) {
        OrderDef d = o.def;
        int bonus = 0;
        if (d.deadlineSeconds() > 0 && ticks - o.issuedTick <= d.deadlineSeconds() * 20L) bonus = d.reward() * d.bonusPercent() / 100;
        String key = "ff:" + team + ":" + d.id() + ":" + f.issued + ":" + o.issuedTick;
        Map<UUID, Integer> shares = ArcadeLogic.split(d.reward(), o.contributors());
        for (var en : shares.entrySet())
            award(key + ":" + en.getKey(), en.getKey(), en.getValue(), PointAward.Reason.OBJECTIVE, "order");
        if (shares.isEmpty()) awardTeam(key, team, d.reward(), PointAward.Reason.OBJECTIVE, "order");
        if (bonus > 0) {
            Map<UUID, Integer> bs = ArcadeLogic.split(bonus, o.contributors());
            for (var en : bs.entrySet())
                award(key + ":b:" + en.getKey(), en.getKey(), en.getValue(), PointAward.Reason.BONUS, "deadline");
        }
        reportObjective(o.contributors().isEmpty() ? null : o.contributors().keySet().iterator().next(), team,
                nl.kmc.core.event.GameObjectiveEvent.Type.ORDER_COMPLETED);
        f.points += d.reward() + bonus;
        f.completed++;
        f.valueDelivered += d.totalItems();
        f.open.remove(o);
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            if (!isParticipant(vid)) continue;
            if (team.equals(teamId(vid))) {
                v.sendMessage(api.tr(vid, "factory.order.done", d.name(), d.reward(), bonus));
                v.sendTitle("§a§l✔ " + d.name(), "§e+" + (d.reward() + bonus), 0, 40, 10);
                v.playSound(v.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }
        issueOrder(team, f);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        double speed = cfgDouble("game.machine-speed", 1.0);
        for (TeamFactory f : factories.values()) {
            for (Machine m : f.machines.values()) m.tick(STEP, speed);
            if (ticks % 20 == 0) {
                for (var en : f.stations.entrySet()) {
                    StationDef def = null;
                    for (StationDef s : model.stations) if (s.id().equals(en.getKey())) def = s;
                    if (def == null || def.restockAmount() <= 0) continue;
                    if (ticks >= f.nextRestock.getOrDefault(en.getKey(), 0L)) {
                        en.getValue().restock(def.restockAmount());
                        f.nextRestock.put(en.getKey(), ticks + def.restockSeconds() * 20L);
                    }
                }
            }
            if (ticks % 10 == 0 && f.dirty) updateBoard(f);
        }
        if (ticks % 10 == 0) refreshMenus();

        long left = Math.max(0, durationTicks - ticks);
        int finalSec = Math.max(5, cfgInt("game.final-countdown-seconds", 30));
        if (!finalAnnounced && left <= finalSec * 20L) {
            finalAnnounced = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isParticipant(p.getUniqueId())) continue;
                p.sendTitle(api.tr(p.getUniqueId(), "factory.final.title"),
                        api.tr(p.getUniqueId(), "factory.final.sub", finalSec), 5, 50, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.6f);
            }
        }
        bar("§6§lFACTORY FRENZY §8| §e" + mmss(left / 20) + (finalAnnounced ? " §8| §c§lFINALE" : ""),
                (double) left / Math.max(1, durationTicks), finalAnnounced ? BarColor.RED : BarColor.YELLOW);
        if (ticks >= durationTicks) end();
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof MachineHolder h)) continue;
            TeamFactory f = factories.get(h.team);
            Machine m = f == null ? null : f.machines.get(h.machine);
            if (m != null) renderMachine(h, p, m);
        }
    }

    private void updateBoard(TeamFactory f) {
        f.dirty = false;
        if (f.board == null || !f.board.isValid()) return;
        Component c = Component.text("BESTELLINGEN", NamedTextColor.GOLD, TextDecoration.BOLD);
        for (Order o : f.open) {
            c = c.append(Component.newline()).append(Component.text(o.def.name() + " (+" + o.def.reward() + ")", NamedTextColor.YELLOW));
            for (var e : o.def.items().entrySet()) {
                int got = Math.min(o.delivered(e.getKey()), e.getValue());
                boolean full = got >= e.getValue();
                c = c.append(Component.newline()).append(Component.text((full ? "✔ " : "• ") + itemName(e.getKey())
                        + " " + got + "/" + e.getValue(), full ? NamedTextColor.GREEN : NamedTextColor.WHITE));
            }
        }
        f.board.text(c);
    }

    // ── HUD / result ──────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        TeamFactory f = factories.get(team);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "factory.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (f != null) {
            int shown = 0;
            for (Order o : f.open) {
                if (shown++ >= 2) break;
                int need = 0, got = 0;
                for (var e : o.def.items().entrySet()) { need += e.getValue(); got += Math.min(o.delivered(e.getKey()), e.getValue()); }
                l.add(api.tr(id, "factory.sb.order", o.def.name(), got, need));
            }
            l.add("");
            l.add(api.tr(id, "factory.sb.team", shownTeamPoints(team)));
            l.add(api.tr(id, "factory.sb.done", f.completed));
        }
        l.add(api.tr(id, "factory.sb.carry", carriedTotal(viewer), cfgInt("game.max-carry", 48)));
        l.add(api.tr(id, "factory.sb.you", delivered.getOrDefault(id, 0)));
        return l;
    }

    @Override
    protected Result finishGame() {
        // a partly delivered order is reported, but earns nothing: only finished production scores
        for (TeamFactory f : factories.values()) {
            for (Order o : f.open) for (var e : o.def.items().entrySet()) f.partialItems += Math.min(o.delivered(e.getKey()), e.getValue());
        }
        List<String> ranked = book.rankTeams(factories.keySet());
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(delivered.getOrDefault(b, 0), delivered.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "factory.results"));
            int i = 1;
            for (String t : ranked) {
                TeamFactory f = factories.get(t);
                if (f == null) continue;
                int eff = f.rawTaken > 0 ? Math.min(100, f.valueDelivered * 100 / f.rawTaken) : 0;
                v.sendMessage(api.tr(vid, "factory.result-line", i++, teamName(t), shownTeamPoints(t), f.completed,
                        f.partialItems, f.itemsDelivered, eff));
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MachineHolder) p.closeInventory();
        }
        for (TeamFactory f : factories.values()) if (f.board != null && f.board.isValid()) f.board.remove();
        for (TextDisplay d : labels) if (d.isValid()) d.remove();
        labels.clear();
        blocks.restoreAll();
        targets.clear();
        factories.clear();
        delivered.clear();
    }
}
