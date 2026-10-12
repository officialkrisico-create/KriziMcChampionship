package nl.kmc.corecharge;

import net.kyori.adventure.text.Component;
import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.CaptureMeter;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArcadeLogic.TeamEnergy;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Core Charge: every team charges its own core with energy from orbs and stations and decides between
 * spending it now (abilities) and keeping it for the Final Charge. Energy is game data, never coins; every
 * point has a key, so each bonus (full charge, final charge, per-10 energy) is paid once.
 */
public final class CoreChargeManager extends ArcadeGameManager {

    public static final String OK = "ok";

    private static final class TeamCore {
        final TeamEnergy energy;
        double collected;
        int paid10;
        boolean fullPaid;
        boolean overloadUsed;
        long surgeUntil, overloadUntil;
        Location where;
        ItemDisplay icon;
        TextDisplay label;
        int orbs, stations;
        TeamCore(double capacity) { energy = new TeamEnergy(capacity); }
    }

    private static final class Station {
        final Location at;
        final CaptureMeter meter;
        long readyAt;
        Station(Location at, CaptureMeter meter) { this.at = at; this.meter = meter; }
    }

    private final CoreChargePlugin plugin;
    private final Map<String, TeamCore> cores = new LinkedHashMap<>();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Integer> contribution = new HashMap<>();
    private List<Location> orbLocs = new ArrayList<>();
    private long[] orbReadyAt = new long[0];
    private ItemDisplay[] orbDisplays = new ItemDisplay[0];
    private final List<Station> stations = new ArrayList<>();
    private long durationTicks;
    private boolean finalAnnounced;
    private int stationCaptures;

    public CoreChargeManager(CoreChargePlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§b§lCORE CHARGE"; }
    @Override protected String introSubKey() { return "@core.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@core.tut.1", "@core.tut.2", "@core.tut.3", "@core.tut.4");
    }

    @Override
    protected Combat combat() {
        return gamePlugin.getConfig().getBoolean("combat.pvp", true) ? Combat.TEAM_PVP : Combat.NONE;
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/corecharge world)");
        if (arena.locs("cores").isEmpty()) p.add("geen kernen (/corecharge core add)");
        if (arena.locs("orbs").isEmpty() && arena.locs("stations").isEmpty())
            p.add("geen energiebronnen: voeg orbs of stations toe");
        return p;
    }

    @Override
    protected List<Location> spawnPoints() {
        List<Location> s = arena.locs("spawns");
        return s.isEmpty() ? arena.locs("cores") : s;
    }

    @Override
    protected Location flyoverCenter() {
        List<Location> c = arena.locs("cores");
        if (c.isEmpty()) return null;
        double x = 0, y = 0, z = 0;
        for (Location l : c) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        return new Location(c.get(0).getWorld(), x / c.size(), y / c.size() + 4, z / c.size());
    }

    @Override
    protected void prepareGame() {
        var cfg = gamePlugin.getConfig();
        cores.clear();
        stations.clear();
        contribution.clear();
        cooldowns.clear();
        finalAnnounced = false;
        stationCaptures = 0;
        durationTicks = cfg.getInt("game.duration-seconds", 180) * 20L;
        List<Location> coreLocs = arena.locs("cores");
        List<String> ids = activeTeams();
        for (int i = 0; i < ids.size(); i++) {
            TeamCore c = new TeamCore(cfg.getDouble("energy.capacity", 100));
            c.where = coreLocs.get(i % coreLocs.size());
            cores.put(ids.get(i), c);
        }
        orbLocs = arena.locs("orbs");
        orbReadyAt = new long[orbLocs.size()];
        orbDisplays = new ItemDisplay[orbLocs.size()];
        for (int i = 0; i < orbLocs.size(); i++) orbDisplays[i] = spawnOrb(orbLocs.get(i));
        for (Location l : arena.locs("stations")) {
            stations.add(new Station(l, new CaptureMeter(cfg.getDouble("game.station-capture-seconds", 8), 1.0)));
        }
        for (var en : cores.entrySet()) buildCore(en.getKey(), en.getValue());
    }

    @Override
    protected void beginPlay() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (combat() == Combat.TEAM_PVP) giveKit(p);
            giveMenuItem(p);
        }
    }

    @Override protected void onRespawn(Player p) { giveMenuItem(p); }

    private void giveMenuItem(Player p) {
        ItemStack star = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = star.getItemMeta();
        m.setDisplayName(api.tr(p.getUniqueId(), "core.menu-item"));
        star.setItemMeta(m);
        p.getInventory().setItem(8, star);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        var cfg = gamePlugin.getConfig();
        double dt = STEP / 20.0;
        double orbR = cfg.getDouble("game.orb-radius", 1.8);

        // orbs
        for (int i = 0; i < orbLocs.size(); i++) {
            if (ticks < orbReadyAt[i]) continue;
            if (orbDisplays[i] == null || !orbDisplays[i].isValid()) orbDisplays[i] = spawnOrb(orbLocs.get(i));
            Location o = orbLocs.get(i);
            for (UUID id : players) {
                if (!isActive(id)) continue;
                TeamCore tc = cores.get(teamId(id));
                if (tc == null || tc.energy.isFull()) continue;       // an orb is never wasted on a full core
                Location l = Bukkit.getPlayer(id).getLocation().add(0, 1, 0);
                if (l.getWorld() != o.getWorld() || l.distanceSquared(o) > orbR * orbR) continue;
                collectOrb(i, id, tc);
                break;
            }
        }

        // stations
        double radius = cfg.getDouble("game.station-radius", 3.5);
        for (int si = 0; si < stations.size(); si++) {
            Station st = stations.get(si);
            if (ticks < st.readyAt) continue;
            Set<String> present = new HashSet<>();
            Map<String, List<UUID>> who = new HashMap<>();
            for (UUID id : players) {
                if (!isActive(id)) continue;
                Location l = Bukkit.getPlayer(id).getLocation();
                if (l.getWorld() == st.at.getWorld() && l.distanceSquared(st.at) <= radius * radius) {
                    present.add(teamId(id));
                    who.computeIfAbsent(teamId(id), k -> new ArrayList<>()).add(id);
                }
            }
            CaptureMeter.Change c = st.meter.update(present, dt);
            if (c != null) {
                onStation(si, st, c.owner(), who.getOrDefault(c.owner(), List.of()));
                st.meter.reset();
                st.readyAt = ticks + cfg.getInt("game.station-cooldown-seconds", 30) * 20L;
            }
        }

        // Final Charge announcement
        long finalTicks = cfg.getInt("game.final-charge-seconds", 30) * 20L;
        if (!finalAnnounced && durationTicks - ticks <= finalTicks) {
            finalAnnounced = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isParticipant(p.getUniqueId())) continue;
                p.sendTitle(api.tr(p.getUniqueId(), "core.final.title"), api.tr(p.getUniqueId(), "core.final.sub"), 5, 60, 15);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);
            }
        }

        if (ticks % 20 == 0) {
            for (var en : cores.entrySet()) refreshCore(en.getKey(), en.getValue());
            drawStations();
        }
        long left = Math.max(0, durationTicks - ticks) / 20;
        bar("§b§lCORE CHARGE §8| §e" + mmss(left) + (finalAnnounced ? " §8| §c§lFINAL CHARGE" : ""),
                1.0 - (double) ticks / Math.max(1, durationTicks),
                finalAnnounced ? BarColor.RED : BarColor.BLUE);
        if (ticks >= durationTicks) end();
    }

    private void collectOrb(int index, UUID collector, TeamCore tc) {
        var cfg = gamePlugin.getConfig();
        String team = teamId(collector);
        orbReadyAt[index] = ticks + cfg.getInt("game.orb-respawn-seconds", 20) * 20L;
        if (orbDisplays[index] != null && orbDisplays[index].isValid()) orbDisplays[index].remove();
        orbDisplays[index] = null;
        gain(team, tc, cfg.getDouble("game.orb-energy", 10));
        tc.orbs++;
        contribution.merge(collector, 1, Integer::sum);
        Player p = Bukkit.getPlayer(collector);
        if (p != null) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
            actionBar(p, api.tr(collector, "core.orb", (int) cfg.getDouble("game.orb-energy", 10)));
        }
    }

    private void onStation(int index, Station st, String team, List<UUID> who) {
        var cfg = gamePlugin.getConfig();
        TeamCore tc = cores.get(team);
        if (tc == null) return;
        int n = ++stationCaptures;
        gain(team, tc, cfg.getDouble("game.station-energy", 20));
        awardTeam("obj:" + index + ":" + n, team, multiplied(team, tc, cfg.getInt("points.objective", 15)),
                PointAward.Reason.OBJECTIVE, "objective");
        tc.stations++;
        for (UUID id : who) contribution.merge(id, 3, Integer::sum);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isParticipant(p.getUniqueId()))
                p.sendMessage(api.tr(p.getUniqueId(), "core.station", teamName(team), index + 1));
        }
    }

    /** Adds energy, handles overflow, the per-10 points and the full-charge bonus. */
    private void gain(String team, TeamCore tc, double amount) {
        var cfg = gamePlugin.getConfig();
        double accepted = tc.energy.add(amount);
        double overflow = amount - accepted;
        tc.collected += accepted;
        int per = Math.max(1, 10);
        while (tc.collected >= (tc.paid10 + 1) * per) {
            tc.paid10++;
            awardTeam("e10:" + team + ":" + tc.paid10, team,
                    multiplied(team, tc, cfg.getInt("points.per-10-energy", 5)), PointAward.Reason.OBJECTIVE, "energy");
        }
        if (overflow > 0 && "CONVERT".equalsIgnoreCase(cfg.getString("game.overflow", "LOST"))) {
            int pts = (int) (overflow / 5);
            if (pts > 0) awardTeam("over:" + team + ":" + ticks, team, pts, PointAward.Reason.BONUS, "overflow");
        }
        if (tc.energy.isFull() && !tc.fullPaid) {
            tc.fullPaid = true;
            awardTeam("full:" + team, team, cfg.getInt("points.full-charge", 30), PointAward.Reason.BONUS, "full-charge");
            for (Player p : Bukkit.getOnlinePlayers())
                if (isParticipant(p.getUniqueId()))
                    p.sendMessage(api.tr(p.getUniqueId(), "core.full", teamName(team)));
        }
    }

    /** Score Surge / Final Overload multiply objective rewards (never the activation bonus itself). */
    private int multiplied(String team, TeamCore tc, int base) {
        double m = 1.0;
        var cfg = gamePlugin.getConfig();
        if (tc.surgeUntil > ticks) m *= cfg.getDouble("abilities.score-surge.multiplier", 2.0);
        if (tc.overloadUntil > ticks) m *= cfg.getDouble("abilities.final-overload.multiplier", 2.0);
        return (int) Math.round(base * m);
    }

    // ── Abilities ─────────────────────────────────────────────────────────────

    boolean isFinalPhase() {
        return live && durationTicks - ticks <= gamePlugin.getConfig().getInt("game.final-charge-seconds", 30) * 20L;
    }

    /** Tries to activate; returns {@link #OK} or the lang key of the refusal. Nothing is spent on a refusal. */
    public String activate(Player p, CoreAbility a) {
        if (!live || !getState().isRunning()) return "core.err.not-running";
        UUID id = p.getUniqueId();
        if (!isActive(id)) return "core.err.not-active";
        String team = teamId(id);
        TeamCore tc = cores.get(team);
        if (tc == null) return "core.err.no-core";
        String err = abilityState(p, a);
        if (!OK.equals(err)) return err;

        var cfg = gamePlugin.getConfig();
        String base = "abilities." + a.key + ".";
        double cost = cfg.getDouble(base + "cost", 20);
        if (!tc.energy.trySpend(cost)) return "core.err.energy";             // atomic
        long dur = cfg.getInt(base + "seconds", 10) * 20L;
        if (a != CoreAbility.FINAL_OVERLOAD)
            cooldowns.start(team + ":" + a.key, ticks, cfg.getInt(base + "cooldown", 20) * 20L);

        switch (a) {
            case SPEED_BOOST -> effect(team, PotionEffectType.SPEED, dur, Math.max(1, cfg.getInt(base + "level", 2)) - 1);
            case SHIELD_PULSE -> effect(team, PotionEffectType.RESISTANCE, dur, 1);
            case SCORE_SURGE -> tc.surgeUntil = ticks + dur;
            case FINAL_OVERLOAD -> {
                tc.overloadUsed = true;
                tc.overloadUntil = ticks + dur;
                awardTeam("overload:" + team, team, cfg.getInt("points.final-charge", 25), PointAward.Reason.BONUS, "final-charge");
            }
        }
        contribution.merge(id, 1, Integer::sum);
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (isParticipant(v.getUniqueId()))
                v.sendMessage(api.tr(v.getUniqueId(), "core.activated", teamName(team),
                        api.tr(v.getUniqueId(), "core.ability." + a.key + ".name")));
        }
        for (UUID m : membersOf(team)) {
            Player mp = Bukkit.getPlayer(m);
            if (mp != null) mp.playSound(mp.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.1f);
        }
        return OK;
    }

    /** Whether the ability can be used right now by this player's team. */
    String abilityState(Player p, CoreAbility a) {
        String team = teamId(p.getUniqueId());
        TeamCore tc = cores.get(team);
        if (tc == null) return "core.err.no-core";
        if (a == CoreAbility.FINAL_OVERLOAD) {
            if (!isFinalPhase()) return "core.err.not-final";
            if (tc.overloadUsed) return "core.err.used";
        } else if (!cooldowns.ready(team + ":" + a.key, ticks)) {
            return "core.err.cooldown";
        }
        if (a == CoreAbility.SCORE_SURGE && tc.surgeUntil > ticks) return "core.err.already-active";
        if (tc.energy.value() + 1e-9 < cost(a)) return "core.err.energy";
        return OK;
    }

    double cost(CoreAbility a) { return gamePlugin.getConfig().getDouble("abilities." + a.key + ".cost", 20); }
    long cooldownSeconds(Player p, CoreAbility a) { return cooldowns.remaining(teamId(p.getUniqueId()) + ":" + a.key, ticks) / 20; }
    double energyOf(Player p) {
        TeamCore tc = cores.get(teamId(p.getUniqueId()));
        return tc != null ? tc.energy.value() : 0;
    }
    double capacity() { return gamePlugin.getConfig().getDouble("energy.capacity", 100); }

    private void effect(String team, PotionEffectType type, long dur, int amp) {
        for (UUID m : membersOf(team)) {
            Player p = Bukkit.getPlayer(m);
            if (p != null && isActive(m)) p.addPotionEffect(new PotionEffect(type, (int) dur, amp, false, true, true));
        }
    }

    @EventHandler
    public void onMenu(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !live || !getState().isRunning()) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isParticipant(p.getUniqueId())) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.NETHER_STAR) return;
        e.setCancelled(true);
        openMenu(p);
    }

    public void openMenu(Player p) { new CoreGui(this, p).open(p); }

    // ── Visuals ───────────────────────────────────────────────────────────────

    private ItemDisplay spawnOrb(Location at) {
        World w = at.getWorld();
        if (w == null) return null;
        return w.spawn(at.clone().add(0, 0.8, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.AMETHYST_SHARD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setGlowing(true);
            d.setPersistent(false);
        });
    }

    private void buildCore(String team, TeamCore tc) {
        World w = tc.where.getWorld();
        if (w == null) return;
        tc.icon = w.spawn(tc.where.clone().add(0, 1.6, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.ENDER_EYE));
            d.setBillboard(Display.Billboard.CENTER);
            d.setGlowing(true);
            d.setGlowColorOverride(colorOf(teamColor(team)));
            d.setPersistent(false);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.Quaternionf(), new org.joml.Vector3f(1.6f, 1.6f, 1.6f), new org.joml.Quaternionf()));
        });
        tc.label = w.spawn(tc.where.clone().add(0, 2.8, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
        });
        refreshCore(team, tc);
        if (!gamePlugin.getConfig().getBoolean("game.show-enemy-energy", true)) {
            for (UUID id : players) {
                if (team.equals(teamId(id))) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null && tc.label != null) p.hideEntity(gamePlugin, tc.label);
            }
        }
    }

    private void refreshCore(String team, TeamCore tc) {
        if (tc.label == null || !tc.label.isValid()) return;
        int filled = (int) Math.round(tc.energy.value() / tc.energy.capacity() * 10);
        String bar = "§b" + "▮".repeat(Math.max(0, filled)) + "§8" + "▮".repeat(Math.max(0, 10 - filled));
        tc.label.text(Component.text(teamName(team) + "\n" + bar + " §f" + (int) tc.energy.value() + "§7/"
                + (int) tc.energy.capacity()
                + (tc.overloadUntil > ticks ? "\n§c§lOVERLOAD" : tc.surgeUntil > ticks ? "\n§e§lSURGE" : "")));
    }

    private void drawStations() {
        World w = arena.world();
        if (w == null) return;
        double r = gamePlugin.getConfig().getDouble("game.station-radius", 3.5);
        for (Station st : stations) {
            boolean ready = ticks >= st.readyAt;
            String owner = st.meter.capturer();
            Color c = !ready ? Color.GRAY : owner != null ? colorOf(teamColor(owner)) : Color.AQUA;
            Particle.DustOptions dust = new Particle.DustOptions(c, 1.4f);
            for (int i = 0; i < 24; i++) {
                double a = 2 * Math.PI * i / 24;
                w.spawnParticle(Particle.DUST, st.at.getX() + Math.cos(a) * r, st.at.getY() + 0.3,
                        st.at.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        TeamCore tc = cores.get(team);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "core.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        if (finalAnnounced) l.add(api.tr(id, "core.sb.final"));
        if (tc != null) {
            l.add(api.tr(id, "core.sb.energy", (int) tc.energy.value(), (int) tc.energy.capacity()));
            if (tc.surgeUntil > ticks) l.add(api.tr(id, "core.sb.surge", (tc.surgeUntil - ticks) / 20));
            if (tc.overloadUntil > ticks) l.add(api.tr(id, "core.sb.overload", (tc.overloadUntil - ticks) / 20));
        }
        l.add("");
        l.add(api.tr(id, "core.sb.team", book.team(team)));
        l.add(api.tr(id, "core.sb.you", contribution.getOrDefault(id, 0)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        var cfg = gamePlugin.getConfig();
        for (var en : cores.entrySet()) {
            int left = (int) (en.getValue().energy.value() / 5);
            if (left > 0)
                awardTeam("left:" + en.getKey(), en.getKey(), left * cfg.getInt("points.per-5-left", 1),
                        PointAward.Reason.BONUS, "energy-left");
        }
        List<String> ranked = book.rankTeams(cores.keySet());
        awardTeamPlacements(ranked);
        List<UUID> order = new ArrayList<>();
        for (String t : ranked) {
            List<UUID> m = membersOf(t);
            m.sort((a, b) -> Integer.compare(contribution.getOrDefault(b, 0), contribution.getOrDefault(a, 0)));
            order.addAll(m);
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "core.results"));
            int i = 1;
            for (String t : ranked) {
                TeamCore tc = cores.get(t);
                v.sendMessage("  §7#" + (i++) + " " + teamName(t) + " §8— §e" + book.team(t) + " ptn §8| §7orbs "
                        + tc.orbs + ", stations " + tc.stations + ", energie " + (int) tc.energy.value());
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
        for (ItemDisplay d : orbDisplays) if (d != null && d.isValid()) d.remove();
        orbDisplays = new ItemDisplay[0];
        for (TeamCore tc : cores.values()) {
            if (tc.icon != null && tc.icon.isValid()) tc.icon.remove();
            if (tc.label != null && tc.label.isValid()) tc.label.remove();
        }
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.SPEED);
            p.removePotionEffect(PotionEffectType.RESISTANCE);
        }
        cores.clear();
        stations.clear();
    }
}
