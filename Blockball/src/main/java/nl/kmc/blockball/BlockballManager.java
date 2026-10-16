package nl.kmc.blockball;

import net.kyori.adventure.text.Component;
import nl.kmc.blockball.BallPhysics.Box;
import nl.kmc.blockball.MatchLogic.*;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArcadeLogic.Region;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Blockball: 4 against 4 on a field with a logical ball. {@link BallPhysics} is the truth, the block display
 * only follows it. Teams are paired into heats; as many heats are played at the same time as there are
 * fields, and the next wave starts when they are finished. Points come from the match result (win, draw,
 * loss) and are booked once per match.
 */
public final class BlockballManager extends ArcadeGameManager {

    // ── Field definition (from the arena config) ──────────────────────────────

    record FieldDef(String id, Region bounds, Region goalA, Region goalB, Location ball,
                    List<Location> spawnsA, List<Location> spawnsB) {}

    private static Box box(Region r) { return new Box(r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ()); }

    // ── A match in progress ───────────────────────────────────────────────────

    private static final class PlayerStat { int goals, assists, shots, passes, saves, catches; }

    private final class Match {
        final int id;
        final FieldDef field;
        final String teamA, teamB;                  // teamB may be null (bye)
        final BallPhysics ball;
        final Box goalA, goalB;
        final MatchState state;
        final TouchTracker touches = new TouchTracker();
        final Set<UUID> sideA = new LinkedHashSet<>(), sideB = new LinkedHashSet<>();
        final Map<UUID, PlayerStat> stats = new HashMap<>();
        BlockDisplay display;
        Quaternionf spin = new Quaternionf();
        long remaining;                             // ticks of regular time (or overtime) left
        long resetAt = -1;                          // when the ball goes back to the centre after a goal
        long kickoffAt;                             // the ball is frozen until then
        boolean goalLocked;
        boolean ended;
        boolean started;                            // the ball has been released after the kick-off countdown
        int lastCountdown = -1;
        UUID keeperA, keeperB;                      // goalkeepers (null = none)
        UUID holder;                                // the keeper who holds the ball right now
        long holdUntil;
        Box zoneA, zoneB;                           // where a keeper may use his hands

        Match(int id, FieldDef field, String a, String b, BallPhysics ball, MatchState state) {
            this.id = id; this.field = field; this.teamA = a; this.teamB = b; this.ball = ball; this.state = state;
            this.goalA = box(field.goalA());
            this.goalB = box(field.goalB());
        }

        int sideOf(UUID id) { return sideA.contains(id) ? 0 : sideB.contains(id) ? 1 : -1; }
        String team(int side) { return side == 0 ? teamA : teamB; }
        PlayerStat stat(UUID id) { return stats.computeIfAbsent(id, k -> new PlayerStat()); }
        Box zone(int side) { return side == 0 ? zoneA : zoneB; }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final BlockballPlugin plugin;
    private List<FieldDef> fields = new ArrayList<>();
    private List<List<Heat>> waves = new ArrayList<>();
    private int waveIndex = -1;
    private final List<Match> matches = new ArrayList<>();
    private final Map<UUID, Match> matchOf = new HashMap<>();
    private final Table table = new Table();
    private final Map<UUID, PlayerStat> totals = new HashMap<>();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Long> sneakSince = new HashMap<>();
    private final Map<UUID, Integer> keeperStints = new HashMap<>();
    private long nextWaveAt = -1;
    private int matchCounter;
    private final java.util.Random rnd = new java.util.Random();

    public BlockballManager(BlockballPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private int cfgInt(String p, int d) { return gamePlugin.getConfig().getInt(p, d); }
    private double cfgDouble(String p, double d) { return gamePlugin.getConfig().getDouble(p, d); }
    private boolean cfgBool(String p, boolean d) { return gamePlugin.getConfig().getBoolean(p, d); }

    /** The fields as configured; every problem found is added to {@code problems}. */
    List<FieldDef> loadFields(List<String> problems) {
        List<FieldDef> out = new ArrayList<>();
        Map<String, Region> bounds = arena.regions("fields");
        Map<String, Region> ga = arena.regions("goal_a"), gb = arena.regions("goal_b");
        for (var en : bounds.entrySet()) {
            String id = en.getKey();
            Region f = en.getValue();
            Region a = ga.get(id), b = gb.get(id);
            Location ball = arena.loc("ball." + id);
            List<Location> sa = arena.locs("spawns_a." + id), sb = arena.locs("spawns_b." + id);
            int before = problems == null ? 0 : problems.size();
            if (a == null) add(problems, "veld " + id + ": geen doel A (/blockball goal " + id + " a)");
            if (b == null) add(problems, "veld " + id + ": geen doel B (/blockball goal " + id + " b)");
            if (ball == null) add(problems, "veld " + id + ": geen bal-spawn (/blockball ball " + id + ")");
            if (sa.isEmpty()) add(problems, "veld " + id + ": geen spawns voor team A (/blockball fspawn " + id + " a)");
            if (sb.isEmpty()) add(problems, "veld " + id + ": geen spawns voor team B (/blockball fspawn " + id + " b)");
            if (a != null && b != null) {
                if (!inside(f, a) || !inside(f, b)) add(problems, "veld " + id + ": een doel ligt buiten het veld");
                if (overlaps(a, b)) add(problems, "veld " + id + ": de doelen overlappen");
            }
            if (ball != null && !f.contains(ball.getX(), ball.getY(), ball.getZ()))
                add(problems, "veld " + id + ": de bal-spawn ligt buiten het veld");
            if (f.sizeX() < 8 || f.sizeZ() < 8) add(problems, "veld " + id + ": het veld is te klein (minimaal 8 x 8)");
            if (problems == null || problems.size() == before) out.add(new FieldDef(id, f, a, b, ball, sa, sb));
        }
        out.sort(Comparator.comparing(FieldDef::id));
        return out;
    }

    private static void add(List<String> l, String s) { if (l != null) l.add(s); }

    private static boolean inside(Region outer, Region in) {
        return in.minX() >= outer.minX() - 1e-6 && in.maxX() <= outer.maxX() + 1e-6
                && in.minZ() >= outer.minZ() - 1e-6 && in.maxZ() <= outer.maxZ() + 1e-6;
    }

    private static boolean overlaps(Region a, Region b) {
        return a.minX() < b.maxX() && a.maxX() > b.minX() && a.minZ() < b.maxZ() && a.maxZ() > b.minZ();
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§a§lBLOCKBALL"; }
    @Override protected String introSubKey() { return "@blockball.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@blockball.tut.1", "@blockball.tut.2", "@blockball.tut.3", "@blockball.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/blockball world)");
        if (arena.regions("fields").isEmpty()) p.add("geen velden (/blockball field add <id>)");
        loadFields(p);
        if (arena.locs("spawns").isEmpty()) p.add("geen wachtplek voor teams die niet spelen (/blockball spawn add)");
        return p;
    }

    @Override
    protected List<Location> spawnPoints() {
        List<Location> s = arena.locs("spawns");
        if (!s.isEmpty()) return s;
        List<Location> fallback = new ArrayList<>();
        for (FieldDef f : loadFields(null)) if (f.ball() != null) fallback.add(f.ball());
        return fallback;
    }

    @Override
    protected Location flyoverCenter() {
        List<FieldDef> f = loadFields(null);
        if (f.isEmpty()) return null;
        Region r = f.get(0).bounds();
        World w = arena.world();
        return w == null ? null : new Location(w, r.centerX(), r.maxY() + 8, r.centerZ());
    }

    // ── Preparation / waves ───────────────────────────────────────────────────

    @Override
    protected void prepareGame() {
        fields = loadFields(null);
        matches.clear();
        matchOf.clear();
        totals.clear();
        cooldowns.clear();
        sneakSince.clear();
        matchCounter = 0;
        waveIndex = -1;
        nextWaveAt = -1;
        waves = MatchLogic.plan(activeTeams(), fields.size());
    }

    @Override
    protected void beginPlay() { startWave(0); }

    private void startWave(int w) {
        waveIndex = w;
        nextWaveAt = -1;
        matches.removeIf(m -> m.ended);
        // everybody who is not playing waits at the waiting spot
        List<Location> wait = arena.locs("spawns");
        int slot = 0;
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            matchOf.remove(id);
            if (!wait.isEmpty()) GamePlayerUtil.safeTeleport(p, spreadAround(wait.get(slot % wait.size()), slot / Math.max(1, wait.size())));
            slot++;
        }
        List<Heat> heats = waves.get(w);
        for (int i = 0; i < heats.size(); i++) {
            Heat h = heats.get(i);
            if (h.bye()) { byeFor(h.a()); continue; }
            startMatch(fields.get(i % fields.size()), h.a(), h.b());
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (isParticipant(v.getUniqueId()))
                v.sendMessage(api.tr(v.getUniqueId(), "blockball.wave", w + 1, waves.size(), matches.size()));
        }
        if (matches.isEmpty()) scheduleNext();
    }

    private void byeFor(String team) {
        int pts = cfgInt("points.bye", cfgInt("points.draw", 225));
        awardShared("bb:bye:" + waveIndex + ":" + team, team, membersOf(team), pts, PointAward.Reason.OBJECTIVE, "match");
        table.record(team, pts, 0, 0, false);
        for (UUID m : membersOf(team)) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(api.tr(m, "blockball.bye", pts));
        }
    }

    private void startMatch(FieldDef f, String a, String b) {
        World w = arena.world();
        if (w == null) return;
        BallPhysics.Config pc = new BallPhysics.Config();
        pc.maxSpeed = cfgDouble("ball.max-speed", pc.maxSpeed);
        pc.gravity = cfgDouble("ball.gravity", pc.gravity);
        pc.groundFriction = cfgDouble("ball.ground-friction", pc.groundFriction);
        pc.airDrag = cfgDouble("ball.air-drag", pc.airDrag);
        pc.bounce = cfgDouble("ball.bounce", pc.bounce);
        pc.wallBounce = cfgDouble("ball.wall-bounce", pc.wallBounce);
        pc.radius = cfgDouble("ball.radius", pc.radius);
        Box fb = box(f.bounds());
        double by = Math.max(f.ball().getY(), fb.minY() + pc.radius);
        BallPhysics ball = new BallPhysics(fb, f.ball().getX(), by, f.ball().getZ(), pc);
        for (Box x : BallPhysics.endZoneBlockers(fb, box(f.goalA()))) ball.addBlocker(x);
        for (Box x : BallPhysics.endZoneBlockers(fb, box(f.goalB()))) ball.addBlocker(x);

        Match m = new Match(++matchCounter, f, a, b, ball,
                new MatchState(cfgInt("match.max-goals", 0), cfgBool("match.overtime.enabled", true)));
        m.remaining = cfgInt("match.duration-seconds", 180) * 20L;
        m.kickoffAt = ticks + cfgInt("match.kickoff-seconds", 4) * 20L;
        ball.setFrozen(true);

        int perTeam = Math.max(1, cfgInt("match.players-per-team", 4));
        for (int side = 0; side < 2; side++) {
            String team = side == 0 ? a : b;
            List<Location> spawns = side == 0 ? f.spawnsA() : f.spawnsB();
            int n = 0;
            for (UUID id : membersOf(team)) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || n >= perTeam) continue;
                (side == 0 ? m.sideA : m.sideB).add(id);
                matchOf.put(id, m);
                GamePlayerUtil.safeTeleport(p, spreadAround(spawns.get(n % spawns.size()), n / spawns.size()));
                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                n++;
            }
        }
        assignKeepers(m);
        spawnDisplay(m);
        matches.add(m);
        for (UUID id : concat(m.sideA, m.sideB)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendTitle(api.tr(id, "blockball.match.title"), api.tr(id, "blockball.match.sub",
                    teamName(a), teamName(b)), 5, 60, 10);
        }
    }

    private static List<UUID> concat(Set<UUID> a, Set<UUID> b) {
        List<UUID> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    // ── Ball display ──────────────────────────────────────────────────────────

    private void spawnDisplay(Match m) {
        World w = arena.world();
        Location at = new Location(w, m.ball.x, m.ball.y, m.ball.z);
        Material mat = Material.matchMaterial(gamePlugin.getConfig().getString("ball.material", "WHITE_CONCRETE"));
        if (mat == null || !mat.isBlock()) mat = Material.WHITE_CONCRETE;
        final Material material = mat;
        float size = (float) (cfgDouble("ball.radius", 0.45) * 2);
        m.display = w.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(material.createBlockData());
            d.setPersistent(false);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setGlowing(true);
            d.setTransformation(new org.bukkit.util.Transformation(new Vector3f(-size / 2, -size / 2, -size / 2),
                    new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
        });
    }

    private void updateDisplay(Match m, double travelled, Vector axis) {
        if (m.display == null || !m.display.isValid()) return;
        float size = (float) (m.ball.cfg.radius * 2);
        if (travelled > 0.001 && axis != null) {
            Quaternionf q = new Quaternionf().rotateAxis((float) (travelled / m.ball.cfg.radius),
                    (float) axis.getX(), 0f, (float) axis.getZ());
            m.spin = q.mul(m.spin).normalize();
        }
        // rotate around the middle of the cube: the translation undoes what the rotation does to the corner
        Vector3f shift = m.spin.transform(new Vector3f(-size / 2, -size / 2, -size / 2), new Vector3f());
        m.display.setTransformation(new org.bukkit.util.Transformation(
                shift, new Quaternionf(m.spin), new Vector3f(size, size, size), new Quaternionf()));
        m.display.teleport(new Location(m.display.getWorld(), m.ball.x, m.ball.y, m.ball.z));
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking()) sneakSince.put(e.getPlayer().getUniqueId(), ticks);
        else sneakSince.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !live || !getState().isRunning()) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Match m = matchOf.get(id);
        if (m == null || m.ended) return;
        Action a = e.getAction();
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        e.setCancelled(true);
        if (!isActive(id) || m.ball.frozen()) return;
        strike(p, m, left);
    }

    private void strike(Player p, Match m, boolean kick) {
        UUID id = p.getUniqueId();
        int side = m.sideOf(id);
        Location pl = p.getLocation();
        boolean keeper = isKeeper(m, id, side) && KeeperLogic.inZone(m.zone(side), pl.getX(), pl.getZ());

        // somebody holds the ball: only the keeper who holds it can do something with it
        if (m.holder != null) {
            if (id.equals(m.holder)) release(m, p, kick, false);
            return;
        }

        double dx = m.ball.x - pl.getX(), dz = m.ball.z - pl.getZ();
        double dist = Math.hypot(dx, dz);
        double range = cfgDouble("controls.range", 2.6) + (keeper ? cfgDouble("keeper.reach-bonus", 1.4) : 0);
        if (dist > range || Math.abs(m.ball.y - (pl.getY() + 0.9)) > 2.4) return;

        Vector look = p.getEyeLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = new Vector(-Math.sin(Math.toRadians(pl.getYaw())), 0, Math.cos(Math.toRadians(pl.getYaw())));
        look.normalize();
        // aim mostly where you look, a little towards the ball so a kick from the side still works
        Vector toBall = new Vector(dx, 0, dz);
        if (toBall.lengthSquared() > 1e-4) toBall.normalize();
        if (dist > 0.4 && look.dot(toBall) < -0.2) return;                 // the ball is behind you
        Vector dir = look.clone().multiply(0.8).add(toBall.clone().multiply(0.2));

        // the keeper uses his hands inside his own area: right-click catches, left-click punches away
        if (keeper) {
            if (!kick && tryCatch(p, m, side, id)) return;
            if (kick && punch(p, m, side, id, look)) return;
        }

        boolean charged = kick && p.isSneaking()
                && ticks - sneakSince.getOrDefault(id, ticks) >= cfgInt("controls.charge-ticks", 14);
        String key = id + (kick ? (charged ? ":shot" : ":kick") : ":pass");
        long cd = kick ? (charged ? cfgInt("controls.shot-cooldown-ticks", 24) : cfgInt("controls.kick-cooldown-ticks", 8))
                : cfgInt("controls.pass-cooldown-ticks", 6);
        if (!cooldowns.ready(key, ticks)) return;
        cooldowns.start(key, ticks, cd);

        double power, lift;
        if (!kick) { power = cfgDouble("controls.pass-power", 9); lift = cfgDouble("controls.pass-lift", 0.8); }
        else if (charged) { power = cfgDouble("controls.shot-power", 20); lift = cfgDouble("controls.shot-lift", 4.5); }
        else { power = cfgDouble("controls.kick-power", 13); lift = cfgDouble("controls.kick-lift", 3.0); }
        m.ball.kick(dir.getX(), dir.getZ(), power, lift);

        m.touches.touch(id.toString(), side, ticks, !kick);
        PlayerStat s = m.stat(id);
        if (kick) s.shots++; else s.passes++;
        World w = p.getWorld();
        w.playSound(pl, charged ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK,
                charged ? 0.5f : 0.7f, charged ? 1.6f : 1.2f);
        w.spawnParticle(Particle.CRIT, m.ball.x, m.ball.y, m.ball.z, charged ? 12 : 5, 0.2, 0.2, 0.2, 0.1);
    }

    // ── Goalkeeper ────────────────────────────────────────────────────────────

    private boolean isKeeper(Match m, UUID id, int side) {
        return side >= 0 && id.equals(side == 0 ? m.keeperA : m.keeperB);
    }

    /** The own goal the ball is going to, from the keeper's point of view. */
    private Region ownGoal(Match m, int side) { return side == 0 ? m.field.goalA() : m.field.goalB(); }

    /** Is the ball, as it is now, a shot on this keeper's goal that an opponent just played? */
    private boolean isSaveNow(Match m, int side) {
        var last = m.touches.last();
        if (last == null) return false;
        Region g = ownGoal(m, side);
        boolean heading = KeeperLogic.headingTo(m.ball.x, m.ball.z, m.ball.vx, m.ball.vz, g.centerX(), g.centerZ());
        return KeeperLogic.isSave(last.side(), side, ticks - last.tick(),
                cfgInt("keeper.save-window-seconds", 3) * 20L, m.ball.speed(),
                cfgDouble("keeper.save-min-speed", 7), heading);
    }

    private void bookSave(Match m, Player p, UUID id) {
        m.stat(id).saves++;
        totals.computeIfAbsent(id, k -> new PlayerStat()).saves++;
        award("save:" + m.id + ":" + ticks + ":" + id, id, cfgInt("points.save", 8), PointAward.Reason.OBJECTIVE, "save");
        p.sendTitle(api.tr(id, "blockball.keeper.save"), api.tr(id, "blockball.keeper.save-sub"), 0, 25, 8);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, m.ball.x, m.ball.y, m.ball.z, 10, 0.3, 0.3, 0.3, 0);
    }

    /** Right-click in the area: take a slow enough ball in the hands. */
    private boolean tryCatch(Player p, Match m, int side, UUID id) {
        if (!cooldowns.ready(id + ":catch", ticks)) return false;
        if (!KeeperLogic.canCatch(m.ball.speed(), cfgDouble("keeper.catch-max-speed", 18), m.ball.y,
                p.getLocation().getY(), 2.4)) return false;
        boolean save = isSaveNow(m, side);
        m.holder = id;
        m.holdUntil = ticks + cfgInt("keeper.hold-seconds", 5) * 20L;
        m.ball.setFrozen(true);
        m.touches.touch(id.toString(), side, ticks, false);
        m.stat(id).catches++;
        totals.computeIfAbsent(id, k -> new PlayerStat()).catches++;
        if (save) bookSave(m, p, id);
        actionBar(p, api.tr(id, "blockball.keeper.caught"));
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.3f);
        return true;
    }

    /** Left-click in the area: a hard clearance, away from the goal. */
    private boolean punch(Player p, Match m, int side, UUID id, Vector look) {
        if (!cooldowns.ready(id + ":punch", ticks)) return false;
        cooldowns.start(id + ":punch", ticks, cfgInt("controls.kick-cooldown-ticks", 8));
        boolean save = isSaveNow(m, side);
        Region g = ownGoal(m, side);
        Vector away = new Vector(m.ball.x - g.centerX(), 0, m.ball.z - g.centerZ());
        if (away.lengthSquared() < 1e-4) away = look.clone(); else away.normalize();
        Vector dir = look.clone().multiply(0.6).add(away.multiply(0.4));
        m.ball.kick(dir.getX(), dir.getZ(), cfgDouble("keeper.punch-power", 15), cfgDouble("keeper.punch-lift", 4.0));
        m.touches.touch(id.toString(), side, ticks, false);
        if (save) bookSave(m, p, id);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.3f);
        p.getWorld().spawnParticle(Particle.CRIT, m.ball.x, m.ball.y, m.ball.z, 8, 0.2, 0.2, 0.2, 0.1);
        return true;
    }

    /** Lets go of the ball: thrown (left-click), rolled (right-click) or forced when time or the area ran out. */
    private void release(Match m, Player p, boolean kick, boolean forced) {
        UUID id = m.holder;
        if (id == null) return;
        int side = m.sideOf(id);
        m.holder = null;
        m.ball.setFrozen(false);
        Vector look = p != null ? p.getEyeLocation().getDirection().setY(0) : new Vector(0, 0, 0);
        if (look.lengthSquared() < 1e-4) {
            Region g = ownGoal(m, side);
            look = new Vector(m.ball.x - g.centerX(), 0, m.ball.z - g.centerZ());
            if (look.lengthSquared() < 1e-4) look = new Vector(1, 0, 0);
        }
        look.normalize();
        boolean thrown = kick && !forced;
        double power = thrown ? cfgDouble("keeper.throw-power", 16) : cfgDouble("controls.pass-power", 9);
        double lift = thrown ? cfgDouble("keeper.throw-lift", 2.0) : cfgDouble("controls.pass-lift", 0.8);
        m.ball.kick(look.getX(), look.getZ(), power, lift);
        m.touches.touch(id.toString(), side, ticks, !thrown);
        cooldowns.start(id + ":catch", ticks, cfgInt("keeper.catch-cooldown-ticks", 40));
        if (p != null) p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.8f, 1.0f);
    }

    /** The ball follows the keeper's hands; the hold ends when the time is up or he is no longer able to. */
    private void tickHold(Match m) {
        UUID id = m.holder;
        Player p = Bukkit.getPlayer(id);
        int side = m.sideOf(id);
        boolean bad = p == null || !isActive(id) || ticks >= m.holdUntil
                || !KeeperLogic.inZone(m.zone(side), p.getLocation().getX(), p.getLocation().getZ());
        if (bad) {
            if (p != null) actionBar(p, api.tr(id, "blockball.keeper.released"));
            release(m, p, false, true);
            return;
        }
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(0, 0, 1);
        d.normalize().multiply(0.8);
        m.ball.x = eye.getX() + d.getX();
        m.ball.y = p.getLocation().getY() + 1.0;
        m.ball.z = eye.getZ() + d.getZ();
    }

    /** Assigns the keepers of a new match (the player with the fewest turns so far) and tells everybody. */
    private void assignKeepers(Match m) {
        if (!"AUTO".equalsIgnoreCase(gamePlugin.getConfig().getString("keeper.mode", "AUTO"))) return;
        int min = cfgInt("keeper.min-team-size", 2);
        double depth = cfgDouble("keeper.zone-depth", 6);
        Box field = box(m.field.bounds());
        m.zoneA = KeeperLogic.zone(m.goalA, field, depth);
        m.zoneB = KeeperLogic.zone(m.goalB, field, depth);
        m.keeperA = KeeperLogic.pick(new ArrayList<>(m.sideA), keeperStints, min);
        m.keeperB = KeeperLogic.pick(new ArrayList<>(m.sideB), keeperStints, min);
        for (UUID k : new UUID[]{m.keeperA, m.keeperB}) {
            if (k == null) continue;
            keeperStints.merge(k, 1, Integer::sum);
            Player p = Bukkit.getPlayer(k);
            if (p != null) p.sendTitle(api.tr(k, "blockball.keeper.you"), api.tr(k, "blockball.keeper.you-sub"), 5, 60, 10);
        }
        for (UUID id : concat(m.sideA, m.sideB)) {
            Player v = Bukkit.getPlayer(id);
            if (v == null) continue;
            if (m.keeperA != null) v.sendMessage(api.tr(id, "blockball.keeper.announce", teamName(m.teamA), nameOf(m.keeperA)));
            if (m.keeperB != null) v.sendMessage(api.tr(id, "blockball.keeper.announce", teamName(m.teamB), nameOf(m.keeperB)));
        }
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        double dt = STEP / 20.0;
        for (Match m : new ArrayList<>(matches)) if (!m.ended) tickMatch(m, dt);

        if (nextWaveAt > 0 && ticks >= nextWaveAt) {
            if (waveIndex + 1 < waves.size()) startWave(waveIndex + 1);
            else end();
        }
        if (ticks % 10 == 0) {
            long left = 0;
            for (Match m : matches) if (!m.ended) left = Math.max(left, m.remaining / 20);
            bar("§a§lBLOCKBALL §8| §7wave §f" + (waveIndex + 1) + "/" + waves.size() + " §8| §e" + mmss(left),
                    1.0, BarColor.GREEN);
        }
    }

    private void tickMatch(Match m, double dt) {
        // kick-off countdown
        if (ticks < m.kickoffAt) {
            int secs = (int) Math.ceil((m.kickoffAt - ticks) / 20.0);
            if (secs != m.lastCountdown) {
                m.lastCountdown = secs;
                for (UUID id : concat(m.sideA, m.sideB)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) { p.sendTitle("§e§l" + secs, "", 0, 25, 0); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1f); }
                }
            }
            updateDisplay(m, 0, null);
            return;
        }
        if (!m.started) {
            m.started = true;
            m.ball.setFrozen(false);
            for (UUID id : concat(m.sideA, m.sideB)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendTitle("§a§lGO!", "", 0, 20, 5);
            }
        }

        double ox = m.ball.x, oz = m.ball.z;
        if (!m.goalLocked) {
            m.remaining -= STEP;
            if (m.holder != null) tickHold(m);
            else dribble(m);
            m.ball.step(dt);
        }
        double mx = m.ball.x - ox, mz = m.ball.z - oz;
        double travelled = Math.hypot(mx, mz);
        Vector axis = travelled > 1e-6 ? new Vector(mz, 0, -mx).normalize() : null;
        updateDisplay(m, travelled, axis);

        // goal (exactly once: the lock stays until the ball is back on the centre spot)
        if (!m.goalLocked && !m.ended && m.holder == null) {
            int goal = m.ball.goalAt(m.goalA, m.goalB);
            if (goal >= 0) onGoal(m, 1 - goal);
        }
        if (m.goalLocked && m.resetAt > 0 && ticks >= m.resetAt) {
            if (m.state.finished()) { finishMatch(m); return; }
            m.ball.reset();
            m.ball.setFrozen(false);
            m.goalLocked = false;
            m.resetAt = -1;
            m.touches.clear();
        }
        // time
        if (!m.goalLocked && m.remaining <= 0 && !m.ended) {
            if (m.state.timeUp()) finishMatch(m);
            else {
                m.remaining = cfgInt("match.overtime.seconds", 60) * 20L;
                for (UUID id : concat(m.sideA, m.sideB)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendTitle(api.tr(id, "blockball.overtime.title"), api.tr(id, "blockball.overtime.sub"), 5, 40, 10);
                }
            }
        }
        // keep the players on the field
        if (ticks % 10 == 0 && !m.ended) keepOnField(m);
        if (ticks % 20 == 0 && !m.ended) chargeEffects(m);
    }

    /** Walking into the ball gives it a light push away from you (and counts as a touch). */
    private void dribble(Match m) {
        double r = cfgDouble("controls.dribble-radius", 1.0);
        for (UUID id : concat(m.sideA, m.sideB)) {
            if (!isActive(id)) continue;
            Player p = Bukkit.getPlayer(id);
            Location l = p.getLocation();
            double dx = m.ball.x - l.getX(), dz = m.ball.z - l.getZ();
            if (Math.hypot(dx, dz) > r || Math.abs(m.ball.y - (l.getY() + 0.5)) > 1.8) continue;
            if (!cooldowns.ready(id + ":dribble", ticks)) continue;
            cooldowns.start(id + ":dribble", ticks, 6);
            m.ball.nudge(dx, dz, cfgDouble("controls.dribble-push", 4.5), cfgDouble("controls.dribble-max", 7));
            m.touches.touch(id.toString(), m.sideOf(id), ticks, false);
        }
    }

    private void keepOnField(Match m) {
        Region b = m.field.bounds();
        for (UUID id : concat(m.sideA, m.sideB)) {
            if (!isActive(id)) continue;
            Player p = Bukkit.getPlayer(id);
            Location l = p.getLocation();
            if (l.getX() >= b.minX() - 1 && l.getX() <= b.maxX() + 1 && l.getZ() >= b.minZ() - 1 && l.getZ() <= b.maxZ() + 1
                    && l.getY() > b.minY() - 3) continue;
            List<Location> sp = m.sideOf(id) == 0 ? m.field.spawnsA() : m.field.spawnsB();
            GamePlayerUtil.safeTeleport(p, sp.get(Math.abs(id.hashCode()) % sp.size()));
            actionBar(p, api.tr(id, "blockball.out"));
        }
    }

    private void chargeEffects(Match m) {
        int need = cfgInt("controls.charge-ticks", 14);
        for (UUID id : concat(m.sideA, m.sideB)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isSneaking()) continue;
            Long since = sneakSince.get(id);
            if (since != null && ticks - since >= need) {
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.2, 0), 6, 0.3, 0.05, 0.3, 0.01);
                actionBar(p, api.tr(id, "blockball.charged"));
            }
        }
    }

    // ── Goals and results ─────────────────────────────────────────────────────

    private void onGoal(Match m, int scoringSide) {
        m.goalLocked = true;
        m.ball.setFrozen(true);
        m.resetAt = ticks + cfgInt("match.goal-reset-seconds", 3) * 20L;
        if (!m.state.goal(scoringSide)) return;
        Credit c = m.touches.credit(scoringSide, ticks, cfgInt("match.assist-window-seconds", 6) * 20L);
        String scorerName = null;
        UUID scorer = null;
        if (c.scorer() != null) {
            scorer = UUID.fromString(c.scorer());
            scorerName = nameOf(scorer);
            m.stat(scorer).goals++;
            totals.computeIfAbsent(scorer, k -> new PlayerStat()).goals++;
            award("goal:" + m.id + ":" + m.state.goals[0] + "-" + m.state.goals[1] + ":" + scorer, scorer,
                    cfgInt("points.goal", 10), PointAward.Reason.OBJECTIVE, "goal");
            reportObjective(scorer, null, nl.kmc.core.event.GameObjectiveEvent.Type.GOAL_SCORED);
        }
        String assistName = null;
        if (c.assist() != null) {
            UUID as = UUID.fromString(c.assist());
            assistName = nameOf(as);
            m.stat(as).assists++;
            totals.computeIfAbsent(as, k -> new PlayerStat()).assists++;
            award("assist:" + m.id + ":" + m.state.goals[0] + "-" + m.state.goals[1] + ":" + as, as,
                    cfgInt("points.assist", 5), PointAward.Reason.ASSIST, "assist");
        }
        String team = m.team(scoringSide);
        final String sn = scorerName, an = assistName;
        for (UUID id : concat(m.sideA, m.sideB)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            String who = c.ownGoal() ? api.tr(id, "blockball.own-goal") : (sn != null ? sn : "?");
            p.sendTitle(api.tr(id, "blockball.goal.title", teamName(team)),
                    api.tr(id, "blockball.goal.sub", who, m.state.goals[0], m.state.goals[1]), 0, 50, 10);
            p.sendMessage(api.tr(id, an != null ? "blockball.goal.chat-assist" : "blockball.goal.chat",
                    teamName(team), who, an != null ? an : "", m.state.goals[0], m.state.goals[1]));
        }
        World w = arena.world();
        Region g = scoringSide == 0 ? m.field.goalB() : m.field.goalA();
        Color col = colorOf(teamColor(team));
        if (w != null) {
            w.playSound(new Location(w, g.centerX(), g.centerY(), g.centerZ()), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2f, 1f);
            w.spawnParticle(Particle.FIREWORK, g.centerX(), g.centerY() + 1, g.centerZ(), 40, 1.2, 1, 1.2, 0.1);
            w.spawnParticle(Particle.DUST, g.centerX(), g.centerY() + 1, g.centerZ(), 40, 1.5, 1.2, 1.5, 0,
                    new Particle.DustOptions(col, 1.8f));
        }
    }

    private void finishMatch(Match m) {
        if (m.ended) return;
        m.ended = true;
        m.holder = null;
        int ga = m.state.goals[0], gb = m.state.goals[1];
        int win = cfgInt("points.win", 300), draw = cfgInt("points.draw", 225), loss = cfgInt("points.loss", 150);
        boolean drawn = m.state.draw();
        int winner = m.state.winner();
        for (int side = 0; side < 2; side++) {
            String team = m.team(side);
            int pts = drawn ? draw : (winner == side ? win : loss);
            awardShared("bb:match:" + m.id + ":" + team, team, membersOf(team), pts, PointAward.Reason.OBJECTIVE, "match");
            table.record(team, pts, m.state.goals[side], m.state.goals[1 - side], !drawn && winner == side);
        }
        for (UUID id : concat(m.sideA, m.sideB)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            String res = drawn ? api.tr(id, "blockball.match-draw")
                    : api.tr(id, "blockball.match-win", teamName(m.team(winner)));
            p.sendMessage(api.tr(id, "blockball.match-result", teamName(m.teamA), ga, gb, teamName(m.teamB), res));
            PlayerStat s = m.stats.get(id);
            if (s != null) p.sendMessage(api.tr(id, "blockball.match-you", s.goals, s.assists, s.shots, s.passes));
            if (s != null && (id.equals(m.keeperA) || id.equals(m.keeperB)))
                p.sendMessage(api.tr(id, "blockball.keeper.summary", s.saves, s.catches));
        }
        if (m.display != null && m.display.isValid()) m.display.remove();
        for (UUID id : concat(m.sideA, m.sideB)) matchOf.remove(id);
        boolean allDone = true;
        for (Match o : matches) if (!o.ended) { allDone = false; break; }
        if (allDone) scheduleNext();
    }

    private void scheduleNext() {
        nextWaveAt = ticks + cfgInt("match.wave-gap-seconds", 6) * 20L;
    }

    // ── HUD / result ──────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        List<String> l = new ArrayList<>();
        Match m = matchOf.get(id);
        l.add(api.tr(id, "blockball.sb.wave", waveIndex + 1, waves.size()));
        if (m != null && !m.ended) {
            l.add(api.tr(id, "blockball.sb.time", mmss(Math.max(0, m.remaining) / 20) + (m.state.overtime() ? " §6OT" : "")));
            l.add(teamName(m.teamA) + " §f" + m.state.goals[0]);
            l.add(teamName(m.teamB) + " §f" + m.state.goals[1]);
            PlayerStat s = m.stats.get(id);
            l.add(api.tr(id, "blockball.sb.you", s == null ? 0 : s.goals, s == null ? 0 : s.assists));
            if (id.equals(m.keeperA) || id.equals(m.keeperB)) l.add(api.tr(id, "blockball.keeper.sb", s == null ? 0 : s.saves));
        } else {
            l.add(api.tr(id, "blockball.sb.waiting"));
            List<String> rank = table.ranking();
            int i = 1;
            for (String t : rank) {
                if (i > 4) break;
                l.add("§7" + i++ + ". " + teamName(t) + " §f" + table.row(t).points);
            }
        }
        return l;
    }

    @Override
    protected Result finishGame() {
        // matches that were still running are decided on the current score
        for (Match m : new ArrayList<>(matches)) {
            if (m.ended) continue;
            m.state.timeUp();
            if (!m.state.finished()) m.state.timeUp();
            finishMatch(m);
        }
        for (String t : activeTeams()) table.row(t);
        List<String> ranked = table.ranking();
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) order.addAll(membersOf(t));
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "blockball.results"));
            int i = 1;
            for (String t : ranked) {
                var s = table.row(t);
                v.sendMessage(api.tr(vid, "blockball.result-line", i++, teamName(t), s.points, s.wins, s.played,
                        s.goalsFor, s.goalsAgainst));
            }
        }
        UUID mvp = null;
        int best = -1;
        for (var en : totals.entrySet()) {
            int score = en.getValue().goals * 2 + en.getValue().assists + en.getValue().saves;
            if (score > best) { best = score; mvp = en.getKey(); }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        return new Result(winner, mvp, mvp != null ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        for (Match m : matches) if (m.display != null && m.display.isValid()) m.display.remove();
        matches.clear();
        matchOf.clear();
        sneakSince.clear();
        keeperStints.clear();
        waves = new ArrayList<>();
    }
}
