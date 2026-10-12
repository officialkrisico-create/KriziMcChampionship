package nl.kmc.rocket;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Rocket Rush: an elytra race through a sky course. Checkpoints have to be passed in order, bonus rings and
 * rocket pickups are optional, the first valid finishes score 100/85/70. Everything that scores goes through
 * {@link #award} so a checkpoint, ring or finish can only be booked once per player per run.
 */
public final class RocketRushManager extends ArcadeGameManager {

    private static final class RS {
        int nextCp;
        final Set<Integer> rings = new HashSet<>();
        final Set<Integer> pickups = new HashSet<>();
        boolean finished;
        boolean dnf;
        int place;
        long finishTick;
        long penaltyTicks;
        Location last;
        long lastMoveTick;
        Location lastSafe;
        int respawns;
    }

    private final RocketRushPlugin plugin;
    private final Map<UUID, RS> state = new HashMap<>();
    private final Set<Integer> claimedPickups = new HashSet<>();
    private List<Location> checkpoints = new ArrayList<>();
    private List<Location> rings = new ArrayList<>();
    private List<Location> pickups = new ArrayList<>();
    private Location finish;
    private int finishedCount;
    private long durationTicks;

    public RocketRushManager(RocketRushPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§b§lROCKET RUSH"; }
    @Override protected String introSubKey() { return "@rocket.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@rocket.tut.1", "@rocket.tut.2", "@rocket.tut.3", "@rocket.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/rocket world)");
        if (arena.locs("spawns").isEmpty()) p.add("geen startposities (/rocket spawn add)");
        if (arena.locs("checkpoints").isEmpty()) p.add("geen checkpoints (/rocket checkpoint add)");
        if (arena.loc("finish") == null) p.add("geen finish (/rocket finish)");
        return p;
    }

    @Override protected List<Location> spawnPoints() { return arena.locs("spawns"); }

    @Override
    protected Location flyoverCenter() {
        Location s = arena.loc("finish");
        List<Location> cps = arena.locs("checkpoints");
        if (!cps.isEmpty()) return cps.get(cps.size() / 2);
        return s;
    }

    @Override
    protected void prepareGame() {
        state.clear();
        claimedPickups.clear();
        finishedCount = 0;
        checkpoints = arena.locs("checkpoints");
        rings = arena.locs("rings");
        pickups = arena.locs("pickups");
        finish = arena.loc("finish");
        durationTicks = gamePlugin.getConfig().getInt("game.duration-seconds", 180) * 20L;
        for (UUID id : players) {
            RS s = new RS();
            state.put(id, s);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                equip(p, gamePlugin.getConfig().getInt("game.rockets-start", 12));
                s.lastSafe = p.getLocation().clone();
            }
        }
    }

    @Override
    protected void beginPlay() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            RS s = state.get(id);
            s.last = p.getLocation().clone();
            s.lastMoveTick = 0;
            p.sendTitle("§a§lGO!", api.tr(id, "rocket.go-sub", checkpoints.size()), 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.2f);
        }
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        var cfg = gamePlugin.getConfig();
        double cpR = cfg.getDouble("game.checkpoint-radius", 8), ringR = cfg.getDouble("game.ring-radius", 4),
                finR = cfg.getDouble("game.finish-radius", 10), pickR = cfg.getDouble("game.pickup-radius", 3);
        double floorY = cfg.getDouble("arena.floor-y", -64);
        long stuckTicks = cfg.getInt("game.stuck-seconds", 12) * 20L;

        int remaining = 0;
        for (UUID id : players) {
            if (!isParticipant(id)) continue;
            RS s = state.get(id);
            Player p = Bukkit.getPlayer(id);
            if (s == null || p == null || s.finished || s.dnf) continue;
            remaining++;
            Location cur = p.getLocation();
            Location prev = s.last != null && s.last.getWorld() == cur.getWorld() ? s.last : cur;

            // checkpoints, strictly in order
            if (s.nextCp < checkpoints.size()) {
                Location cp = checkpoints.get(s.nextCp);
                if (hits(prev, cur, cp, cpR)) {
                    if (award("cp:" + id + ":" + s.nextCp, id, cfg.getInt("points.checkpoint", 10),
                            PointAward.Reason.OBJECTIVE, "checkpoint")) {
                        actionBar(p, api.tr(id, "rocket.checkpoint", s.nextCp + 1, checkpoints.size()));
                        p.playSound(cur, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
                    }
                    s.lastSafe = cp.clone();
                    s.nextCp++;
                }
            }
            // rings
            for (int i = 0; i < rings.size(); i++) {
                if (s.rings.contains(i)) continue;
                if (hits(prev, cur, rings.get(i), ringR)
                        && award("ring:" + id + ":" + i, id, cfg.getInt("points.ring", 15),
                        PointAward.Reason.BONUS, "ring")) {
                    s.rings.add(i);
                    p.playSound(cur, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                    actionBar(p, api.tr(id, "rocket.ring", s.rings.size(), rings.size()));
                }
            }
            // pickups
            boolean roundMode = "ROUND".equalsIgnoreCase(cfg.getString("game.pickup-mode", "PLAYER"));
            for (int i = 0; i < pickups.size(); i++) {
                if (s.pickups.contains(i) || (roundMode && claimedPickups.contains(i))) continue;
                if (hits(prev, cur, pickups.get(i), pickR)) {
                    s.pickups.add(i);
                    if (roundMode) claimedPickups.add(i);
                    int n = cfg.getInt("game.pickup-rockets", 4);
                    p.getInventory().addItem(rockets(n));
                    p.playSound(cur, Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
                    actionBar(p, api.tr(id, "rocket.pickup", n));
                }
            }
            // finish: only after every checkpoint
            if (s.nextCp >= checkpoints.size() && finish != null && hits(prev, cur, finish, finR)) {
                finishPlayer(id, p, s);
                s.last = cur.clone();
                continue;
            }
            // fell / stuck
            if (cur.getY() < floorY) { recover(id, p, s, "rocket.fell"); continue; }
            if (p.isOnGround() && !p.isGliding() && cur.distanceSquared(prev) < 0.04) {
                if (ticks - s.lastMoveTick > stuckTicks) { recover(id, p, s, "rocket.stuck"); continue; }
            } else {
                s.lastMoveTick = ticks;
            }
            // keep the elytra on
            ItemStack chest = p.getInventory().getChestplate();
            if (chest == null || chest.getType() != Material.ELYTRA) p.getInventory().setChestplate(elytra());
            s.last = cur.clone();
        }

        if (ticks % 10 == 0) drawGuides();
        updateBar();
        if (ticks >= durationTicks || remaining == 0) end();
    }

    private static boolean hits(Location a, Location b, Location c, double r) {
        if (c.getWorld() != b.getWorld()) return false;
        return ArcadeLogic.segmentHitsSphere(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(),
                c.getX(), c.getY(), c.getZ(), r);
    }

    private void finishPlayer(UUID id, Player p, RS s) {
        s.finished = true;
        s.finishTick = ticks;
        s.place = ++finishedCount;
        var cfg = gamePlugin.getConfig();
        List<Integer> table = cfg.getIntegerList("points.finish-positions");
        int posPts = s.place <= table.size() ? table.get(s.place - 1) : 0;
        award("finish-pos:" + id, id, posPts, PointAward.Reason.PLACEMENT, "finish-position");
        award("finish-bonus:" + id, id, cfg.getInt("points.finish-bonus", 25), PointAward.Reason.OBJECTIVE, "finish-bonus");
        try { api.games().announceFinish(id, s.place, players.size(), registration.getId()); } catch (Throwable ignored) { }
        p.sendTitle("§6§l#" + s.place, "§e" + mmss((s.finishTick + s.penaltyTicks) / 20), 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        tellKey("rocket.finished", p.getName(), s.place);
        p.getInventory().setChestplate(null);
        p.getInventory().clear();
        p.setGliding(false);
        Location wait = finish.clone().add(0, 1, 0);
        GamePlayerUtil.safeTeleport(p, wait);
    }

    private void recover(UUID id, Player p, RS s, String msgKey) {
        var cfg = gamePlugin.getConfig();
        if (!cfg.getBoolean("game.respawn-enabled", true)) {
            s.dnf = true;
            p.sendMessage(api.tr(id, "rocket.dnf"));
            p.setGameMode(GameMode.SPECTATOR);
            return;
        }
        s.respawns++;
        s.penaltyTicks += cfg.getInt("game.respawn-penalty-seconds", 5) * 20L;
        Location to = s.lastSafe != null ? s.lastSafe.clone() : spawnPoints().get(0);
        if (s.nextCp < checkpoints.size()) {
            Location next = checkpoints.get(s.nextCp);
            to.setDirection(next.toVector().subtract(to.toVector()));
        }
        p.setGliding(false);
        p.setFallDistance(0);
        GamePlayerUtil.safeTeleport(p, to);
        int have = countRockets(p), min = cfg.getInt("game.respawn-min-rockets", 3);
        if (have < min) p.getInventory().addItem(rockets(min - have));
        p.getInventory().setChestplate(elytra());
        s.last = to.clone();
        s.lastMoveTick = ticks;
        actionBar(p, api.tr(id, msgKey));
    }

    // ── Equipment ─────────────────────────────────────────────────────────────

    private ItemStack elytra() {
        ItemStack it = new ItemStack(Material.ELYTRA);
        ItemMeta m = it.getItemMeta();
        m.setUnbreakable(true);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack rockets(int n) {
        ItemStack it = new ItemStack(Material.FIREWORK_ROCKET, Math.max(1, Math.min(64, n)));
        FireworkMeta m = (FireworkMeta) it.getItemMeta();
        m.setPower(Math.max(1, Math.min(3, gamePlugin.getConfig().getInt("game.rocket-power", 2))));
        it.setItemMeta(m);
        return it;
    }

    private void equip(Player p, int rocketCount) {
        p.getInventory().clear();
        p.getInventory().setChestplate(elytra());
        p.getInventory().addItem(rockets(rocketCount));
        p.setFoodLevel(20);
        p.setHealth(20);
    }

    private int countRockets(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == Material.FIREWORK_ROCKET) n += it.getAmount();
        return n;
    }

    // ── Visuals / HUD ─────────────────────────────────────────────────────────

    /** Shows each player the next checkpoint (and the rings they still miss) as particle rings. */
    private void drawGuides() {
        double cpR = gamePlugin.getConfig().getDouble("game.checkpoint-radius", 8);
        double ringR = gamePlugin.getConfig().getDouble("game.ring-radius", 4);
        for (UUID id : players) {
            RS s = state.get(id);
            Player p = Bukkit.getPlayer(id);
            if (s == null || p == null || s.finished) continue;
            Location target = s.nextCp < checkpoints.size() ? checkpoints.get(s.nextCp) : finish;
            if (target != null) circle(p, target, cpR * 0.8, Particle.END_ROD);
            for (int i = 0; i < rings.size(); i++) {
                if (s.rings.contains(i)) continue;
                Location r = rings.get(i);
                if (r.getWorld() == p.getWorld() && r.distanceSquared(p.getLocation()) < 120 * 120)
                    circle(p, r, ringR, Particle.HAPPY_VILLAGER);
            }
        }
    }

    private void circle(Player viewer, Location c, double r, Particle particle) {
        if (c.getWorld() != viewer.getWorld()) return;
        int points = 24;
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double x = Math.cos(a) * r, y = Math.sin(a) * r;
            viewer.spawnParticle(particle, c.getX() + x, c.getY() + y, c.getZ(), 1, 0, 0, 0, 0);
            viewer.spawnParticle(particle, c.getX(), c.getY() + y, c.getZ() + x, 1, 0, 0, 0, 0);
        }
    }

    private void updateBar() {
        long left = Math.max(0, durationTicks - ticks) / 20;
        bar("§b§lROCKET RUSH §8| §e" + mmss(left) + " §8| §7" + finishedCount + "/" + players.size() + " §7finish",
                1.0 - (double) ticks / Math.max(1, durationTicks), left <= 30 ? BarColor.RED : BarColor.BLUE);
    }

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        RS s = state.get(id);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "rocket.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (s != null) {
            l.add(api.tr(id, "rocket.sb.checkpoint", Math.min(s.nextCp + 1, checkpoints.size()), checkpoints.size()));
            l.add(api.tr(id, "rocket.sb.rings", s.rings.size(), rings.size()));
            l.add(api.tr(id, "rocket.sb.rockets", countRockets(viewer)));
            l.add(s.finished ? api.tr(id, "rocket.sb.finished", s.place)
                    : s.dnf ? api.tr(id, "rocket.sb.dnf") : api.tr(id, "rocket.sb.racing", livePosition(id)));
            l.add(api.tr(id, "rocket.sb.points", book.player(id)));
        }
        return l;
    }

    /** Position among all racers by progress (finished first, then checkpoints, then distance to the next one). */
    private int livePosition(UUID id) {
        List<UUID> order = ranking();
        return Math.max(1, order.indexOf(id) + 1);
    }

    private List<UUID> ranking() {
        List<UUID> list = new ArrayList<>(players);
        list.sort((a, b) -> {
            RS x = state.get(a), y = state.get(b);
            if (x == null || y == null) return 0;
            if (x.finished != y.finished) return x.finished ? -1 : 1;
            if (x.finished) return Integer.compare(x.place, y.place);
            if (x.nextCp != y.nextCp) return Integer.compare(y.nextCp, x.nextCp);
            return Double.compare(distToNext(a, x), distToNext(b, y));
        });
        return list;
    }

    private double distToNext(UUID id, RS s) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return Double.MAX_VALUE;
        Location t = s.nextCp < checkpoints.size() ? checkpoints.get(s.nextCp) : finish;
        if (t == null || t.getWorld() != p.getWorld()) return Double.MAX_VALUE;
        return t.distance(p.getLocation());
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        for (UUID id : players) {
            RS s = state.get(id);
            if (s != null && !s.finished) s.dnf = true;     // time is up: DNF, keeps checkpoint/ring points
        }
        List<UUID> finishers = new ArrayList<>();
        List<UUID> others = new ArrayList<>();
        for (UUID id : players) {
            if (state.get(id) != null && state.get(id).finished) finishers.add(id); else others.add(id);
        }
        finishers.sort(Comparator.comparingInt(a -> state.get(a).place));
        others.sort((a, b) -> {
            RS x = state.get(a), y = state.get(b);
            if (x.nextCp != y.nextCp) return Integer.compare(y.nextCp, x.nextCp);
            if (x.rings.size() != y.rings.size()) return Integer.compare(y.rings.size(), x.rings.size());
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });
        List<UUID> order = new ArrayList<>(finishers);
        order.addAll(others);
        awardPlayerPlacements(order);

        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "rocket.results"));
            for (int i = 0; i < Math.min(8, order.size()); i++) {
                UUID id = order.get(i);
                RS s = state.get(id);
                String how = s.finished ? "§a" + mmss((s.finishTick + s.penaltyTicks) / 20)
                        : "§7DNF (" + s.nextCp + "/" + checkpoints.size() + ")";
                v.sendMessage("  §7#" + (i + 1) + " §f" + nameOf(id) + " §8— " + how + " §8| §e" + book.player(id) + " ptn");
            }
        }
        UUID top = order.isEmpty() ? null : order.get(0);
        return new Result(top != null ? nameOf(top) : "-", top, top != null ? nameOf(top) : null, order);
    }

    @Override
    protected void cleanupGame() {
        state.clear();
        claimedPickups.clear();
    }
}
