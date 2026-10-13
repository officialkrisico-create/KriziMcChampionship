package nl.kmc.zonezero;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic;
import nl.kmc.game.api.arcade.ArcadeLogic.CaptureMeter;
import nl.kmc.game.api.arcade.ArcadeLogic.CaptureStatus;
import nl.kmc.game.api.arcade.ArcadeLogic.Region;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Zone Zero: a handful of zones, two of them open at a time. A team captures a zone by being the only team
 * inside for the capture time; open zones that are held pay out every score interval. The open zones rotate,
 * so a team has to keep moving. All rules sit in {@link CaptureMeter} (tested); this class wires it to the world.
 */
public final class ZoneZeroManager extends ArcadeGameManager {

    private static final class Zone {
        final String id;
        final Region region;
        final CaptureMeter meter;
        String name;
        double multiplier = 1;
        int speed;
        boolean open;
        int changes;
        Zone(String id, Region region, CaptureMeter meter) { this.id = id; this.region = region; this.meter = meter; }
    }

    private final ZoneZeroPlugin plugin;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private List<String> openIds = new ArrayList<>();
    private final Map<UUID, Integer> contribution = new HashMap<>();
    private final Random rnd = new Random();
    private long durationTicks;
    private long nextRotateTick;
    private boolean warned;
    private int rotations;

    public ZoneZeroManager(ZoneZeroPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§5§lZONE ZERO"; }
    @Override protected String introSubKey() { return "@zonezero.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@zonezero.tut.1", "@zonezero.tut.2", "@zonezero.tut.3", "@zonezero.tut.4");
    }
    @Override protected Combat combat() { return Combat.TEAM_PVP; }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/zonezero world)");
        int n = arena.regions("zones").size();
        int need = Math.max(1, gamePlugin.getConfig().getInt("game.active-zones", 2));
        if (n == 0) p.add("geen zones (/zonezero zone add <id>)");
        else if (n <= need) p.add("minstens " + (need + 1) + " zones nodig voor wisselende zones (er zijn er " + n + ")");
        if (arena.locs("spawns").isEmpty()) p.add("geen spawns (/zonezero spawn add)");
        return p;
    }

    @Override protected List<Location> spawnPoints() { return arena.locs("spawns"); }

    @Override
    protected Location flyoverCenter() {
        World w = arena.world();
        var rs = arena.regions("zones").values();
        if (w == null || rs.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Region r : rs) { x += r.centerX(); y += r.centerY(); z += r.centerZ(); }
        return new Location(w, x / rs.size(), y / rs.size() + 5, z / rs.size());
    }

    @Override
    protected void prepareGame() {
        var cfg = gamePlugin.getConfig();
        zones.clear();
        contribution.clear();
        double cap = cfg.getDouble("game.capture-seconds", 5);
        double decay = cfg.getDouble("game.decay-per-second", 1.0);
        for (var en : arena.regions("zones").entrySet()) {
            Zone z = new Zone(en.getKey(), en.getValue(), new CaptureMeter(cap, decay));
            z.name = cfg.getString("zone-props." + en.getKey() + ".name", en.getKey());
            z.multiplier = cfg.getDouble("zone-props." + en.getKey() + ".score-multiplier", 1.0);
            z.speed = cfg.getInt("zone-props." + en.getKey() + ".speed", 0);
            zones.put(en.getKey(), z);
        }
        durationTicks = cfg.getInt("game.duration-seconds", 180) * 20L;
        openIds = new ArrayList<>();
        rotations = 0;
    }

    @Override
    protected void beginPlay() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) giveKit(p);
        }
        rotate(true);
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    private void rotate(boolean first) {
        var cfg = gamePlugin.getConfig();
        int count = Math.max(1, Math.min(cfg.getInt("game.active-zones", 2), zones.size() - (zones.size() > 1 ? 1 : 0)));
        List<String> all = new ArrayList<>(zones.keySet());
        List<String> next;
        if ("FIXED".equalsIgnoreCase(cfg.getString("game.rotation", "RANDOM"))) {
            next = new ArrayList<>();
            for (int i = 0; i < count; i++) next.add(all.get((rotations * count + i) % all.size()));
        } else {
            next = ArcadeLogic.pickDistinct(all, count, openIds, rnd);
        }
        rotations++;

        boolean reset = cfg.getBoolean("game.reset-owner-on-close", true);
        for (String id : openIds) {
            Zone z = zones.get(id);
            if (z == null) continue;
            z.open = false;
            if (reset) z.meter.reset();
        }
        openIds = next;
        for (String id : openIds) {
            Zone z = zones.get(id);
            if (z != null) { z.open = true; z.meter.reset(); }
        }
        nextRotateTick = ticks + cfg.getInt("game.rotate-seconds", 30) * 20L;
        warned = false;
        if (!first) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isParticipant(p.getUniqueId())) continue;
                p.sendTitle(api.tr(p.getUniqueId(), "zonezero.rotate.title"),
                        "§e" + String.join("§7, §e", openNames()), 5, 50, 10);
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
            }
        }
    }

    private List<String> openNames() {
        List<String> names = new ArrayList<>();
        for (String id : openIds) names.add(zones.get(id).name);
        return names;
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        var cfg = gamePlugin.getConfig();
        double dt = STEP / 20.0;

        // capture meters of the open zones
        for (String id : openIds) {
            Zone z = zones.get(id);
            Map<String, List<UUID>> present = new HashMap<>();
            for (UUID pid : players) {
                if (!isActive(pid)) continue;
                Player p = Bukkit.getPlayer(pid);
                Location l = p.getLocation();
                if (z.region.contains(l.getX(), l.getY(), l.getZ()))
                    present.computeIfAbsent(teamId(pid), k -> new ArrayList<>()).add(pid);
            }
            CaptureMeter.Change change = z.meter.update(present.keySet(), dt);
            if (change != null) onCaptured(z, change, present.getOrDefault(change.owner(), List.of()));
            if (z.speed > 0 && ticks % 20 == 0) {
                for (List<UUID> list : present.values())
                    for (UUID pid : list) {
                        Player p = Bukkit.getPlayer(pid);
                        if (p != null) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, z.speed - 1, false, false, false));
                    }
            }
        }

        // score interval
        long interval = cfg.getInt("game.score-interval-seconds", 10) * 20L;
        if (interval > 0 && ticks > 0 && ticks % interval == 0) scoreInterval();

        // rotation + warning
        if (!warned && nextRotateTick - ticks <= cfg.getInt("game.warn-seconds", 5) * 20L) {
            warned = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isParticipant(p.getUniqueId())) continue;
                actionBar(p, api.tr(p.getUniqueId(), "zonezero.rotate.warn", String.join(", ", openNames())));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            }
        }
        if (ticks >= nextRotateTick && ticks < durationTicks - 20) rotate(false);

        if (ticks % 10 == 0) drawZones();
        long left = Math.max(0, durationTicks - ticks) / 20;
        bar("§5§lZONE ZERO §8| §e" + mmss(left) + " §8| §7wissel over §f" + Math.max(0, (nextRotateTick - ticks) / 20) + "s",
                1.0 - (double) ticks / Math.max(1, durationTicks), left <= 20 ? BarColor.RED : BarColor.PURPLE);
        if (ticks >= durationTicks) end();
    }

    private void onCaptured(Zone z, CaptureMeter.Change c, List<UUID> present) {
        var cfg = gamePlugin.getConfig();
        int n = ++z.changes;
        int cap = (int) Math.round(cfg.getInt("points.capture", 20) * z.multiplier);
        // the players who stood in the zone get the points themselves (the team gets them through them)
        awardShared("cap:" + z.id + ":" + n, c.owner(), present, cap, PointAward.Reason.OBJECTIVE, "capture");
        if (c.previous() != null) {
            awardShared("take:" + z.id + ":" + n, c.owner(), present, cfg.getInt("points.takeover", 10),
                    PointAward.Reason.BONUS, "takeover");
        }
        // personal contribution: everybody standing in the zone when it flipped
        for (UUID id : present) contribution.merge(id, 3, Integer::sum);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(p.getUniqueId())) continue;
            p.sendMessage(api.tr(p.getUniqueId(), c.previous() != null ? "zonezero.takeover" : "zonezero.captured",
                    teamName(c.owner()), z.name, c.previous() != null ? teamName(c.previous()) : ""));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        }
    }

    private void scoreInterval() {
        var cfg = gamePlugin.getConfig();
        Map<String, Integer> held = new HashMap<>();
        for (String id : openIds) {
            Zone z = zones.get(id);
            String owner = z.meter.owner();
            if (owner == null || z.meter.status() == CaptureStatus.CONTESTED) continue;
            int pts = (int) Math.round(cfg.getInt("points.hold", 10) * z.multiplier);
            List<UUID> inside = new ArrayList<>();
            for (UUID pid : membersOf(owner)) {
                Player p = Bukkit.getPlayer(pid);
                if (p != null && isActive(pid) && z.region.contains(p.getLocation().getX(), p.getLocation().getY(),
                        p.getLocation().getZ())) inside.add(pid);
            }
            if (awardShared("hold:" + z.id + ":" + ticks, owner, inside, pts, PointAward.Reason.OBJECTIVE, "hold")) {
                held.merge(owner, 1, Integer::sum);
                for (UUID pid : inside) contribution.merge(pid, 1, Integer::sum);
            }
        }
        // all open zones at once (and every one of them paid this interval)
        for (var en : held.entrySet()) {
            if (en.getValue() == openIds.size() && openIds.size() > 0) {
                List<UUID> inAny = new ArrayList<>();
                for (UUID pid : membersOf(en.getKey())) {
                    Player p = Bukkit.getPlayer(pid);
                    if (p == null || !isActive(pid)) continue;
                    for (String zid : openIds) {
                        Zone oz = zones.get(zid);
                        if (oz != null && oz.region.contains(p.getLocation().getX(), p.getLocation().getY(),
                                p.getLocation().getZ())) { inAny.add(pid); break; }
                    }
                }
                awardShared("all:" + ticks + ":" + en.getKey(), en.getKey(), inAny, cfg.getInt("points.all-active", 30),
                        PointAward.Reason.BONUS, "all-active");
                for (Player p : Bukkit.getOnlinePlayers())
                    if (isParticipant(p.getUniqueId()))
                        p.sendMessage(api.tr(p.getUniqueId(), "zonezero.all", teamName(en.getKey())));
            }
        }
    }

    // ── Visuals / HUD ─────────────────────────────────────────────────────────

    private void drawZones() {
        World w = arena.world();
        if (w == null) return;
        for (Zone z : zones.values()) {
            Region r = z.region;
            Color color;
            Particle.DustOptions dust;
            if (!z.open) {
                dust = new Particle.DustOptions(Color.GRAY, 1.0f);
            } else if (z.meter.status() == CaptureStatus.CONTESTED) {
                dust = new Particle.DustOptions(Color.WHITE, 1.4f);
            } else {
                String owner = z.meter.owner() != null ? z.meter.owner() : z.meter.capturer();
                color = owner != null ? colorOf(teamColor(owner)) : Color.YELLOW;
                dust = new Particle.DustOptions(color, 1.4f);
            }
            double step = Math.max(2, Math.min(4, Math.max(r.sizeX(), r.sizeZ()) / 8));
            double y0 = r.minY() + 0.3, y1 = Math.min(r.maxY(), r.minY() + 3);
            for (double x = r.minX(); x <= r.maxX(); x += step) {
                for (double y = y0; y <= y1; y += 1.5) {
                    w.spawnParticle(Particle.DUST, x, y, r.minZ(), 1, 0, 0, 0, 0, dust);
                    w.spawnParticle(Particle.DUST, x, y, r.maxZ(), 1, 0, 0, 0, 0, dust);
                }
            }
            for (double zz = r.minZ(); zz <= r.maxZ(); zz += step) {
                for (double y = y0; y <= y1; y += 1.5) {
                    w.spawnParticle(Particle.DUST, r.minX(), y, zz, 1, 0, 0, 0, 0, dust);
                    w.spawnParticle(Particle.DUST, r.maxX(), y, zz, 1, 0, 0, 0, 0, dust);
                }
            }
        }
    }

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "zonezero.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        l.add(api.tr(id, "zonezero.sb.next", Math.max(0, (nextRotateTick - ticks) / 20)));
        for (String zid : openIds) {
            Zone z = zones.get(zid);
            String state;
            if (z.meter.status() == CaptureStatus.CONTESTED) state = "§c⚔";
            else if (z.meter.owner() != null) state = teamName(z.meter.owner());
            else if (z.meter.capturer() != null) state = "§e" + (int) Math.round(z.meter.fraction() * 100) + "%";
            else state = "§7-";
            l.add("§f" + z.name + " " + state);
        }
        l.add("");
        l.add(api.tr(id, "zonezero.sb.team", shownTeamPoints(team)));
        l.add(api.tr(id, "zonezero.sb.you", contribution.getOrDefault(id, 0)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        List<String> ranked = book.rankTeams(activeTeams());
        awardTeamPlacements(ranked);

        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(contribution.getOrDefault(b, 0), contribution.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "zonezero.results"));
            int i = 1;
            for (String t : ranked) {
                v.sendMessage("  §7#" + (i++) + " " + teamName(t) + " §8— §e" + shownTeamPoints(t) + " ptn §8(§7capture "
                        + book.categoryTotal(t, "capture") + ", hold " + book.categoryTotal(t, "hold") + "§8)");
            }
        }
        UUID mvp = null;
        int best = -1;
        for (UUID id : players) {
            int c = contribution.getOrDefault(id, 0);
            if (c > best) { best = c; mvp = id; }
        }
        String winner = ranked.isEmpty() ? "-" : teamName(ranked.get(0));
        return new Result(winner, mvp, mvp != null ? nameOf(mvp) : null, order);
    }

    @Override
    protected void cleanupGame() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.removePotionEffect(PotionEffectType.SPEED);
        }
        zones.clear();
        openIds.clear();
    }
}
