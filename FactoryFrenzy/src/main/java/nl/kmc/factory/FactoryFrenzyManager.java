package nl.kmc.factory;

import net.kyori.adventure.text.Component;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.factory.Kitchen.Kind;
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
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Factory Frenzy, Overcooked style: every team runs its own kitchen. Ingredients come from crates, are chopped
 * on boards and cooked in pots and pans (food that is left alone burns), a dish is put together on a plate and
 * served at the hatch against the open orders, and the dirty plate comes back to the sink where it has to be
 * washed before it can be used again. A player carries one thing at a time, so the team has to pass work around.
 * Every click is resolved by {@link Kitchen}; stations are real blocks placed for the game and restored afterwards.
 */
public final class FactoryFrenzyManager extends ArcadeGameManager {

    // ── Config model ──────────────────────────────────────────────────────────

    record ItemDef(String id, String name, Material material) {}
    record DishDef(String id, String name, int reward, int seconds, int unlockAfter, int weight) {}
    record StationDef(Kind kind, String id, int[] at, String item, String name) {}

    /** Everything read from config.yml, plus the problems found while reading it. */
    static final class Model {
        final Map<String, ItemDef> items = new LinkedHashMap<>();
        final Map<String, DishDef> dishes = new LinkedHashMap<>();
        final List<StationDef> stations = new ArrayList<>();
        final Map<String, Material> blocks = new HashMap<>();
        Kitchen.Config kitchen;
        int[] board = {0, 0, -6}, spawn = {0, 0, 5};
        final List<String> problems = new ArrayList<>();
    }

    // ── Runtime ───────────────────────────────────────────────────────────────

    private record Target(String team, String station) {}

    private static final class Order {
        final String dish;
        final long issued;
        final int limitTicks;
        Order(String dish, long issued, int limitTicks) { this.dish = dish; this.issued = issued; this.limitTicks = limitTicks; }
    }

    private static final class TeamKitchen {
        Location origin;
        final Map<String, Kitchen.Station> stations = new LinkedHashMap<>();
        final Map<String, TextDisplay> labels = new HashMap<>();
        final Set<String> dirtyLabels = new HashSet<>();
        Kitchen.Team shared;
        final List<Order> open = new ArrayList<>();
        final List<Long> dirtyReturns = new ArrayList<>();
        final Map<UUID, Integer> contrib = new HashMap<>();
        int served, expired, streak, issued, points;
        TextDisplay board;
        boolean boardDirty = true;
    }

    private final FactoryFrenzyPlugin plugin;
    private Model model = new Model();
    private final Map<String, TeamKitchen> kitchens = new LinkedHashMap<>();
    private final Map<Long, Target> targets = new HashMap<>();
    private final TemporaryBlocks blocks = new TemporaryBlocks();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, String> hand = new HashMap<>();
    private final Map<UUID, Integer> actions = new HashMap<>();
    private final Map<UUID, Integer> served = new HashMap<>();
    private final Random rnd = new Random();
    private long durationTicks;
    private boolean finalAnnounced;

    public FactoryFrenzyManager(FactoryFrenzyPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Config loading and validation ─────────────────────────────────────────

    private int cfgInt(String p, int d) { return gamePlugin.getConfig().getInt(p, d); }

    Model loadModel() { return parse(gamePlugin.getConfig()); }

    private static int[] pos(Object o) {
        if (o instanceof List<?> l && l.size() >= 3 && l.get(0) instanceof Number a && l.get(1) instanceof Number b && l.get(2) instanceof Number c)
            return new int[]{a.intValue(), b.intValue(), c.intValue()};
        return null;
    }

    private static Map<String, Integer> amounts(Object o) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k).toLowerCase(Locale.ROOT), v instanceof Number n ? n.intValue() : 1));
        return out;
    }

    /** Reads and validates the whole kitchen definition (no server needed: tests use this too). */
    static Model parse(org.bukkit.configuration.file.FileConfiguration cfg) {
        Model m = new Model();

        ConfigurationSection items = cfg.getConfigurationSection("items");
        if (items != null) for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            Material mat = Material.matchMaterial(s == null ? "" : s.getString("material", ""));
            if (mat == null || mat.name().startsWith("LEGACY_") || mat == Material.AIR) { m.problems.add("items." + id + ": onbekend materiaal"); continue; }
            m.items.put(id.toLowerCase(Locale.ROOT), new ItemDef(id.toLowerCase(Locale.ROOT), s.getString("name", id), mat));
        }

        Map<String, String> chop = new LinkedHashMap<>();
        ConfigurationSection ch = cfg.getConfigurationSection("chopping");
        if (ch != null) for (String k : ch.getKeys(false)) chop.put(k.toLowerCase(Locale.ROOT), String.valueOf(ch.getString(k)).toLowerCase(Locale.ROOT));

        List<Kitchen.CookRecipe> cook = new ArrayList<>();
        for (Object o : cfg.getList("cooking", List.of())) {
            if (!(o instanceof Map<?, ?> r)) continue;
            int secs = r.get("seconds") instanceof Number n ? n.intValue() : 10;
            cook.add(new Kitchen.CookRecipe(String.valueOf(r.get("station")).toLowerCase(Locale.ROOT), amounts(r.get("in")),
                    String.valueOf(r.get("out")).toLowerCase(Locale.ROOT), Math.max(1, secs * 20),
                    Boolean.TRUE.equals(r.get("on-plate"))));
        }

        List<Kitchen.Assembly> assemblies = new ArrayList<>();
        ConfigurationSection dishes = cfg.getConfigurationSection("dishes");
        if (dishes != null) for (String id : dishes.getKeys(false)) {
            ConfigurationSection s = dishes.getConfigurationSection(id);
            if (s == null) continue;
            String did = id.toLowerCase(Locale.ROOT);
            Material mat = Material.matchMaterial(s.getString("material", "BOWL"));
            m.items.put("dish_" + did, new ItemDef("dish_" + did, s.getString("name", id), mat != null ? mat : Material.BOWL));
            m.dishes.put(did, new DishDef(did, s.getString("name", id), s.getInt("reward", 100), s.getInt("seconds", 90),
                    s.getInt("unlock-after", 0), Math.max(1, s.getInt("weight", 1))));
            List<String> parts = s.getStringList("parts");
            if (!parts.isEmpty()) {
                Map<String, Integer> p = new LinkedHashMap<>();
                for (String part : parts) p.merge(part.toLowerCase(Locale.ROOT), 1, Integer::sum);
                assemblies.add(new Kitchen.Assembly(did, p));
            }
        }

        ConfigurationSection rules = cfg.getConfigurationSection("rules");
        int chopClicks = rules != null ? rules.getInt("chop-clicks", 5) : 5;
        int washClicks = rules != null ? rules.getInt("wash-clicks", 4) : 4;
        int burn = (rules != null ? rules.getInt("burn-seconds", 10) : 10) * 20;
        int plates = rules != null ? rules.getInt("start-plates", 4) : 4;
        int dirtyDelay = (rules != null ? rules.getInt("dirty-delay-seconds", 8) : 8) * 20;
        m.kitchen = new Kitchen.Config(chop, cook, assemblies, chopClicks, washClicks, burn, plates, dirtyDelay);

        ConfigurationSection bl = cfg.getConfigurationSection("blocks");
        if (bl != null) for (String k : bl.getKeys(false)) {
            Material mat = Material.matchMaterial(bl.getString(k, ""));
            if (mat != null && !mat.name().startsWith("LEGACY_")) m.blocks.put(k.toLowerCase(Locale.ROOT), mat);
        }

        // the built-in items every kitchen needs
        m.items.putIfAbsent("plate", new ItemDef("plate", "Bord", Material.BOWL));
        m.items.putIfAbsent("dirty_plate", new ItemDef("dirty_plate", "Vies bord", Material.BRICK));

        ConfigurationSection layout = cfg.getConfigurationSection("layout");
        Set<String> crates = new HashSet<>();
        Set<Kind> kinds = new HashSet<>();
        Set<String> potTypes = new HashSet<>();
        if (layout != null) {
            for (Object o : layout.getList("stations", List.of())) {
                if (!(o instanceof Map<?, ?> s)) continue;
                int[] at = pos(s.get("at"));
                String id = String.valueOf(s.get("id"));
                if (at == null) { m.problems.add("layout.stations: positie ontbreekt bij " + id); continue; }
                Kind kind;
                try { kind = Kind.valueOf(String.valueOf(s.get("kind")).toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { m.problems.add("layout.stations " + id + ": onbekende soort " + s.get("kind")); continue; }
                String item = s.get("item") == null ? "" : String.valueOf(s.get("item")).toLowerCase(Locale.ROOT);
                if (kind == Kind.POT && item.isEmpty()) item = "pot";
                if (kind == Kind.CRATE) {
                    if (!m.items.containsKey(item)) m.problems.add("krat " + id + ": onbekend item " + item);
                    crates.add(item);
                }
                if (kind == Kind.POT) potTypes.add(item);
                kinds.add(kind);
                m.stations.add(new StationDef(kind, id, at, item, s.get("name") != null ? String.valueOf(s.get("name")) : id));
            }
            m.board = layout.getIntegerList("board").size() >= 3 ? new int[]{layout.getIntegerList("board").get(0),
                    layout.getIntegerList("board").get(1), layout.getIntegerList("board").get(2)} : m.board;
            m.spawn = layout.getIntegerList("spawn").size() >= 3 ? new int[]{layout.getIntegerList("spawn").get(0),
                    layout.getIntegerList("spawn").get(1), layout.getIntegerList("spawn").get(2)} : m.spawn;
        }
        // every item used in a recipe must be known
        for (var e : chop.entrySet()) {
            if (!m.items.containsKey(e.getKey())) m.problems.add("chopping: onbekend item " + e.getKey());
            if (!m.items.containsKey(e.getValue())) m.problems.add("chopping: onbekend item " + e.getValue());
        }
        for (Kitchen.CookRecipe r : cook) {
            for (String i : r.in().keySet()) if (!m.items.containsKey(i)) m.problems.add("cooking: onbekend item " + i);
            if (!m.items.containsKey(r.out())) m.problems.add("cooking: onbekend item " + r.out());
        }
        for (Kitchen.Assembly a : assemblies) for (String i : a.parts().keySet())
            if (!m.items.containsKey(i)) m.problems.add("gerecht " + a.id() + ": onbekend onderdeel " + i);
        if (m.dishes.isEmpty()) m.problems.add("geen gerechten in config.yml (dishes)");
        if (m.stations.isEmpty()) m.problems.add("geen stations in layout.stations");
        m.problems.addAll(Kitchen.validate(m.kitchen, crates, kinds, potTypes, m.dishes.keySet()));
        return m;
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
        if (arena.locs("factories").isEmpty()) p.add("geen keukens (/factory factory add)");
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
        kitchens.clear();
        targets.clear();
        hand.clear();
        actions.clear();
        served.clear();
        cooldowns.clear();
        model = loadModel();
        durationTicks = cfgInt("game.duration-seconds", 420) * 20L;
        finalAnnounced = false;

        List<Location> origins = arena.locs("factories");
        List<String> ids = activeTeams();
        if (ids.size() > origins.size()) {
            String msg = "§c[Factory Frenzy] " + ids.size() + " teams maar " + origins.size()
                    + " keukens — elk team heeft een eigen keuken nodig (/factory factory add).";
            for (Player op : Bukkit.getOnlinePlayers()) if (op.isOp() || op.hasPermission("factoryfrenzy.admin")) op.sendMessage(msg);
            log.severe(msg);
            Bukkit.getScheduler().runTask(gamePlugin, this::end);
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            String team = ids.get(i);
            TeamKitchen k = new TeamKitchen();
            k.origin = origins.get(i);
            k.shared = new Kitchen.Team(model.kitchen.startPlates());
            build(team, k);
            kitchens.put(team, k);
        }
    }

    private Location at(TeamKitchen k, int[] o) { return k.origin.clone().add(o[0], o[1], o[2]); }

    private Material blockOf(StationDef d) {
        if (d.kind() == Kind.POT) {
            Material m = model.blocks.get(d.item());
            if (m != null) return m;
        }
        Material m = model.blocks.get(d.kind().name().toLowerCase(Locale.ROOT));
        if (m != null) return m;
        return switch (d.kind()) {
            case CRATE -> Material.BARREL;
            case BOARD -> Material.OAK_PLANKS;
            case POT -> "pan".equals(d.item()) ? Material.SMOKER : Material.CAULDRON;
            case COUNTER -> Material.QUARTZ_BLOCK;
            case SINK -> Material.WATER_CAULDRON;
            case RACK -> Material.WHITE_CONCRETE;
            case SERVE -> Material.GOLD_BLOCK;
            case TRASH -> Material.COMPOSTER;
        };
    }

    private void build(String team, TeamKitchen k) {
        for (StationDef d : model.stations) {
            Location l = at(k, d.at());
            blocks.set(l, blockOf(d));
            k.stations.put(d.id(), new Kitchen.Station(d.kind(), d.id(), d.item()));
            targets.put(key(l), new Target(team, d.id()));
            TextDisplay label = l.getWorld().spawn(l.clone().add(0.5, 1.45, 0.5), TextDisplay.class, t -> {
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            });
            k.labels.put(d.id(), label);
            k.dirtyLabels.add(d.id());
        }
        Location bl = at(k, model.board).add(0.5, 2.4, 0.5);
        k.board = bl.getWorld().spawn(bl, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(170, 0, 0, 0));
        });
    }

    private static long key(Location l) { return key(l.getBlockX(), l.getBlockY(), l.getBlockZ()); }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private String itemName(String id) {
        if (id == null) return "-";
        ItemDef d = model.items.get(id);
        return d != null ? d.name() : id;
    }

    @Override
    protected void beginPlay() {
        int open = Math.max(1, cfgInt("orders.open", 3));
        for (var en : kitchens.entrySet()) {
            for (int i = 0; i < open; i++) issueOrder(en.getKey(), en.getValue());
            for (UUID m : membersOf(en.getKey())) {
                Player p = Bukkit.getPlayer(m);
                if (p == null) continue;
                p.sendTitle(api.tr(m, "factory.go.title"), api.tr(m, "factory.go.sub"), 5, 50, 10);
                setHand(p, null);
            }
        }
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    private void issueOrder(String team, TeamKitchen k) {
        Set<String> openDishes = new HashSet<>();
        for (Order o : k.open) openDishes.add(o.dish);
        List<DishDef> pool = new ArrayList<>();
        for (DishDef d : model.dishes.values()) {
            if (k.served < d.unlockAfter()) continue;
            for (int i = 0; i < d.weight() * (openDishes.contains(d.id()) ? 1 : 3); i++) pool.add(d);
        }
        if (pool.isEmpty()) return;
        DishDef d = pool.get(rnd.nextInt(pool.size()));
        double scale = Math.max(0.5, cfgDouble("orders.time-scale", 1.0));
        k.open.add(new Order(d.id(), ticks, (int) (d.seconds() * 20 * scale)));
        k.issued++;
        k.boardDirty = true;
        for (UUID m : membersOf(team)) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) {
                p.sendMessage(api.tr(m, "factory.order.new", d.name(), d.reward(), d.seconds()));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.4f);
            }
        }
    }

    private double cfgDouble(String p, double d) { return gamePlugin.getConfig().getDouble(p, d); }

    // ── The hand: one thing at a time ─────────────────────────────────────────

    private ItemStack stack(String id) {
        ItemDef d = model.items.get(id);
        ItemStack it = new ItemStack(d != null ? d.material() : Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§b§l" + (d != null ? d.name() : id));
        meta.setLore(List.of("§8Factory Frenzy"));
        it.setItemMeta(meta);
        return it;
    }

    /** What the player carries (null = empty). The server keeps the truth; the hotbar only shows it. */
    private void setHand(Player p, String item) {
        if (item == null) hand.remove(p.getUniqueId()); else hand.put(p.getUniqueId(), item);
        var inv = p.getInventory();
        inv.clear();
        if (item != null) inv.setItem(0, stack(item));
        inv.setHeldItemSlot(0);
        p.updateInventory();
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (getState().isRunning() && isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (getState().isRunning() && isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInventory(InventoryClickEvent e) {
        if (getState().isRunning() && e.getWhoClicked() instanceof Player p && isParticipant(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (getState().isRunning() && e.getEntity() instanceof Player p && isParticipant(p.getUniqueId())) e.setCancelled(true);
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
        TeamKitchen k = kitchens.get(team);
        Kitchen.Station st = k == null ? null : k.stations.get(t.station());
        if (st == null) return;
        if (!cooldowns.ready(id + ":click", ticks)) return;
        cooldowns.start(id + ":click", ticks, 3);

        String held = hand.get(id);
        Kitchen.Result r = Kitchen.interact(model.kitchen, k.shared, st, held);
        if (r.serve() != null) { serve(p, team, k, r.serve()); return; }
        if (!Objects.equals(r.hand(), held)) setHand(p, r.hand());
        if (r.msg() != null) actionBar(p, api.tr(id, "factory.msg." + r.msg(), itemName(r.hand() != null ? r.hand() : held)));
        play(p, r.sound());
        if (r.changed()) {
            k.contrib.merge(id, 1, Integer::sum);
            actions.merge(id, 1, Integer::sum);
            k.dirtyLabels.add(st.id);
            if (st.kind == Kind.RACK || st.kind == Kind.SINK || st.kind == Kind.TRASH) {
                for (var s : k.stations.values()) if (s.kind == Kind.RACK || s.kind == Kind.SINK) k.dirtyLabels.add(s.id);
            }
        }
    }

    private void play(Player p, String sound) {
        if (sound == null) return;
        Sound s = switch (sound) {
            case "pickup" -> Sound.ENTITY_ITEM_PICKUP;
            case "place" -> Sound.BLOCK_WOOD_PLACE;
            case "chop" -> Sound.ENTITY_PLAYER_ATTACK_SWEEP;
            case "chopped" -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            case "cook" -> Sound.ITEM_FLINTANDSTEEL_USE;
            case "ready" -> Sound.BLOCK_NOTE_BLOCK_PLING;
            case "wash" -> Sound.ENTITY_PLAYER_SPLASH;
            case "washed" -> Sound.ENTITY_PLAYER_LEVELUP;
            case "bin" -> Sound.BLOCK_COMPOSTER_FILL;
            default -> Sound.ENTITY_VILLAGER_NO;
        };
        float pitch = sound.equals("chop") ? 1.5f : sound.equals("washed") ? 1.6f : 1.1f;
        p.playSound(p.getLocation(), s, 0.7f, pitch);
    }

    // ── Serving and scoring ───────────────────────────────────────────────────

    private void serve(Player p, String team, TeamKitchen k, String dish) {
        UUID id = p.getUniqueId();
        Order match = null;
        for (Order o : k.open) if (o.dish.equals(dish)) { match = o; break; }
        if (match == null) {
            actionBar(p, api.tr(id, "factory.serve.nobody", itemName("dish_" + dish)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        DishDef d = model.dishes.get(dish);
        double left = Math.max(0, 1.0 - (double) (ticks - match.issued) / Math.max(1, match.limitTicks));
        int tip = (int) Math.round(d.reward() * cfgDouble("orders.tip-percent", 50) / 100.0 * left);
        k.streak++;
        int combo = (int) Math.round(d.reward() * Math.min(5, k.streak - 1) * cfgDouble("orders.combo-percent", 10) / 100.0);
        int total = d.reward() + tip + combo;

        String key = "ff:" + team + ":" + k.issued + ":" + match.issued + ":" + dish + ":" + k.served;
        Map<UUID, Integer> shares = ArcadeLogic.split(total, new HashMap<>(k.contrib));
        for (var en : shares.entrySet()) award(key + ":" + en.getKey(), en.getKey(), en.getValue(), PointAward.Reason.OBJECTIVE, "dish");
        if (shares.isEmpty()) awardTeam(key, team, total, PointAward.Reason.OBJECTIVE, "dish");
        reportObjective(id, team, nl.kmc.core.event.GameObjectiveEvent.Type.ORDER_COMPLETED);
        served.merge(id, 1, Integer::sum);
        k.contrib.clear();
        k.points += total;
        k.served++;
        k.open.remove(match);
        k.dirtyReturns.add(ticks + model.kitchen.dirtyDelayTicks());      // the plate comes back dirty
        k.boardDirty = true;
        setHand(p, null);
        for (UUID m : membersOf(team)) {
            Player v = Bukkit.getPlayer(m);
            if (v == null) continue;
            v.sendMessage(api.tr(m, "factory.order.done", d.name(), d.reward(), tip, combo, total));
            v.sendTitle("§a§l✔ " + d.name(), "§e+" + total + (k.streak > 1 ? " §6(x" + k.streak + ")" : ""), 0, 40, 10);
            v.playSound(v.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        issueOrder(team, k);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        for (var en : kitchens.entrySet()) {
            TeamKitchen k = en.getValue();
            for (Kitchen.Station s : k.stations.values()) {
                if (Kitchen.tick(model.kitchen, s)) k.dirtyLabels.add(s.id);
                if (s.burnt && ticks % 10 == 0) smoke(k, s);
            }
            // dirty plates come back to the sink
            if (!k.dirtyReturns.isEmpty()) {
                var it = k.dirtyReturns.iterator();
                while (it.hasNext()) {
                    if (ticks >= it.next()) {
                        it.remove();
                        k.shared.dirty++;
                        for (var s : k.stations.values()) if (s.kind == Kind.SINK || s.kind == Kind.RACK) k.dirtyLabels.add(s.id);
                    }
                }
            }
            // orders that ran out of time
            for (Order o : new ArrayList<>(k.open)) {
                if (ticks - o.issued < o.limitTicks) continue;
                k.open.remove(o);
                k.expired++;
                k.streak = 0;
                k.boardDirty = true;
                for (UUID m : membersOf(en.getKey())) {
                    Player v = Bukkit.getPlayer(m);
                    if (v == null) continue;
                    v.sendMessage(api.tr(m, "factory.order.expired", itemName("dish_" + o.dish)));
                    v.playSound(v.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.7f);
                }
                issueOrder(en.getKey(), k);
            }
            if (ticks % 10 == 0) {
                for (String id : new ArrayList<>(k.dirtyLabels)) updateLabel(k, id);
                k.dirtyLabels.clear();
                if (!k.open.isEmpty() || k.boardDirty) updateBoard(k);
            }
        }

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

    private void smoke(TeamKitchen k, Kitchen.Station s) {
        for (StationDef d : model.stations) {
            if (!d.id().equals(s.id)) continue;
            Location l = at(k, d.at()).add(0.5, 1.0, 0.5);
            l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 6, 0.2, 0.3, 0.2, 0.01);
            l.getWorld().spawnParticle(Particle.FLAME, l, 3, 0.15, 0.1, 0.15, 0.01);
        }
    }

    private static String bar(int done, int total) {
        int filled = Math.max(0, Math.min(10, done * 10 / Math.max(1, total)));
        return "§a" + "█".repeat(filled) + "§8" + "█".repeat(10 - filled);
    }

    private void updateLabel(TeamKitchen k, String id) {
        TextDisplay d = k.labels.get(id);
        Kitchen.Station s = k.stations.get(id);
        if (d == null || !d.isValid() || s == null) return;
        String name = "";
        for (StationDef sd : model.stations) if (sd.id().equals(id)) name = sd.name();
        String text = switch (s.kind) {
            case CRATE -> "§e§l" + itemName(s.item);
            case BOARD -> "§6" + name + (s.board == null ? "\n§8leeg"
                    : "\n§f" + itemName(s.board) + (model.kitchen.chop().containsKey(s.board)
                    ? "\n" + bar(s.chops, model.kitchen.chopClicks()) : "\n§a✔"));
            case POT -> {
                String body;
                if (s.burnt) body = "\n§c§lVERBRAND! §7klik om te legen";
                else if (s.cooking != null) body = "\n§e" + itemName(s.cooking.out()) + " §7" + Math.max(0, s.cookLeft / 20) + "s";
                else if (s.ready != null) body = "\n§a§l" + itemName(s.ready) + " klaar!" + (model.kitchen.isPlateable(s.ready) ? " §7(bord)" : "");
                else if (!s.contents.isEmpty()) {
                    StringBuilder sb = new StringBuilder("\n§f");
                    for (String c : s.contents) sb.append(itemName(c)).append(' ');
                    body = sb.toString().trim();
                } else body = "\n§8leeg";
                yield "§6" + name + body;
            }
            case COUNTER -> {
                String body;
                if (s.dish != null) body = "\n§a" + itemName("dish_" + s.dish);
                else if (s.plate) {
                    StringBuilder sb = new StringBuilder("\n§fBord");
                    for (String c : s.parts) sb.append(" + ").append(itemName(c));
                    body = sb.toString();
                } else if (s.loose != null) body = "\n§f" + itemName(s.loose);
                else body = "\n§8leeg";
                yield "§6" + name + body;
            }
            case SINK -> "§b" + name + "\n§7vuil: §f" + k.shared.dirty + (k.shared.dirty > 0 ? " §8(" + k.shared.washProgress + "/" + model.kitchen.washClicks() + ")" : "");
            case RACK -> "§f" + name + "\n§7borden: §f" + k.shared.plates;
            case SERVE -> "§a§l" + name;
            case TRASH -> "§7" + name;
        };
        d.text(Component.text(text));
    }

    private void updateBoard(TeamKitchen k) {
        k.boardDirty = false;
        if (k.board == null || !k.board.isValid()) return;
        StringBuilder sb = new StringBuilder("§6§lBESTELLINGEN");
        for (Order o : k.open) {
            DishDef d = model.dishes.get(o.dish);
            int left = Math.max(0, (int) ((o.limitTicks - (ticks - o.issued)) / 20));
            String col = left < 15 ? "§c" : left < 30 ? "§e" : "§a";
            sb.append("\n").append(col).append(d != null ? d.name() : o.dish).append(" §7").append(left).append("s");
        }
        if (k.streak > 1) sb.append("\n§6combo x").append(k.streak);
        k.board.text(Component.text(sb.toString()));
    }

    // ── HUD / result ──────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        TeamKitchen k = kitchens.get(team);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "factory.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (k != null) {
            l.add(api.tr(id, "factory.sb.served", k.served));
            l.add(api.tr(id, "factory.sb.open", k.open.size()));
            l.add(api.tr(id, "factory.sb.plates", k.shared.plates, k.shared.dirty));
            if (k.streak > 1) l.add(api.tr(id, "factory.sb.combo", k.streak));
        }
        l.add(api.tr(id, "factory.sb.hand", itemName(hand.get(id))));
        return l;
    }

    @Override
    protected Result finishGame() {
        List<String> ranked = book.rankTeams(kitchens.keySet());
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(actions.getOrDefault(b, 0), actions.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "factory.results"));
            int i = 1;
            for (String t : ranked) {
                TeamKitchen k = kitchens.get(t);
                if (k == null) continue;
                v.sendMessage(api.tr(vid, "factory.result-line", i++, teamName(t), shownTeamPoints(t), k.served, k.expired));
            }
        }
        UUID mvp = null;
        int best = -1;
        for (UUID id : players) {
            int c = served.getOrDefault(id, 0) * 3 + actions.getOrDefault(id, 0);
            if (c > best) { best = c; mvp = id; }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        return new Result(winner, mvp, mvp != null && best > 0 ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        for (TeamKitchen k : kitchens.values()) {
            if (k.board != null && k.board.isValid()) k.board.remove();
            for (TextDisplay d : k.labels.values()) if (d.isValid()) d.remove();
        }
        for (Player p : Bukkit.getOnlinePlayers()) if (hand.containsKey(p.getUniqueId())) p.getInventory().clear();
        blocks.restoreAll();
        targets.clear();
        kitchens.clear();
        hand.clear();
        actions.clear();
        served.clear();
    }
}
