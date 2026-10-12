package nl.kmc.crown;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.CaptureMeter;
import nl.kmc.game.api.arcade.ArcadeLogic.CaptureStatus;
import nl.kmc.game.api.arcade.ArcadeLogic.Cooldowns;
import nl.kmc.game.api.arcade.ArcadeLogic.Region;
import nl.kmc.game.api.arcade.ArcadeLogic.TeamEnergy;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
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
 * Crown Control: one capture zone, one crown. The holder team earns points and energy; energy buys short
 * team abilities that only the holder can use. Spending is atomic ({@link TeamEnergy#trySpend}): a refused
 * activation never costs anything.
 */
public final class CrownControlManager extends ArcadeGameManager {

    private final CrownControlPlugin plugin;
    private CaptureMeter meter;
    private Region crownZone;
    private ItemDisplay crown;
    private long durationTicks;
    private long lockUntil;
    private long neutralAt;
    private int changes;

    private final Map<String, TeamEnergy> energy = new HashMap<>();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<String, Long> doubleUntil = new HashMap<>();
    private final Map<String, Long> overdriveUntil = new HashMap<>();
    private final Map<String, Long> fortifyUntil = new HashMap<>();
    private String lockdownBy;
    private long lockdownUntil;
    private String bountyBy, bountyTarget;
    private long bountyUntil;
    private final Map<UUID, Integer> contribution = new HashMap<>();
    private final Random rnd = new Random();

    public CrownControlManager(CrownControlPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§6§lCROWN CONTROL"; }
    @Override protected String introSubKey() { return "@crown.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@crown.tut.1", "@crown.tut.2", "@crown.tut.3", "@crown.tut.4");
    }
    @Override protected Combat combat() { return Combat.TEAM_PVP; }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/crown world)");
        if (arena.region("zone") == null) p.add("geen kroon-zone (/crown pos1 + pos2 + zone)");
        if (arena.locs("spawns").isEmpty()) p.add("geen spawns (/crown spawn add)");
        return p;
    }

    @Override protected List<Location> spawnPoints() { return arena.locs("spawns"); }

    @Override
    protected Location flyoverCenter() {
        Region r = arena.region("zone");
        World w = arena.world();
        return r == null || w == null ? null : new Location(w, r.centerX(), r.centerY() + 3, r.centerZ());
    }

    @Override
    protected void prepareGame() {
        var cfg = gamePlugin.getConfig();
        crownZone = arena.region("zone");
        meter = new CaptureMeter(cfg.getDouble("game.capture-seconds", 5), cfg.getDouble("game.decay-per-second", 1));
        durationTicks = cfg.getInt("game.duration-seconds", 180) * 20L;
        energy.clear();
        cooldowns.clear();
        doubleUntil.clear();
        overdriveUntil.clear();
        fortifyUntil.clear();
        contribution.clear();
        lockdownBy = null;
        lockdownUntil = 0;
        bountyBy = bountyTarget = null;
        bountyUntil = 0;
        lockUntil = 0;
        neutralAt = 0;
        changes = 0;
        for (String t : activeTeams()) energy.put(t, new TeamEnergy(cfg.getDouble("energy.capacity", 100)));
        ensureCrown();
    }

    @Override
    protected void beginPlay() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            giveKit(p);
            giveMenuItem(p);
        }
    }

    @Override protected void onRespawn(Player p) { giveMenuItem(p); }

    private void giveMenuItem(Player p) {
        ItemStack star = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = star.getItemMeta();
        m.setDisplayName(api.tr(p.getUniqueId(), "crown.menu-item"));
        star.setItemMeta(m);
        p.getInventory().setItem(8, star);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        var cfg = gamePlugin.getConfig();
        double dt = STEP / 20.0;
        if (ticks % 20 == 0) ensureCrown();

        // who stands in the zone
        Map<String, List<UUID>> present = new HashMap<>();
        for (UUID id : players) {
            if (!isActive(id)) continue;
            Location l = Bukkit.getPlayer(id).getLocation();
            if (crownZone.contains(l.getX(), l.getY(), l.getZ()))
                present.computeIfAbsent(teamId(id), k -> new ArrayList<>()).add(id);
        }
        if (ticks >= lockUntil) {
            CaptureMeter.Change c = meter.update(present.keySet(), dt);
            if (c != null) onCaptured(c, present.getOrDefault(c.owner(), List.of()));
        }
        if (meter.owner() != null && neutralAt > 0 && ticks >= neutralAt) {
            meter.reset();
            neutralAt = 0;
            tellKey("crown.neutral");
        }

        // hold points + energy
        long interval = cfg.getInt("game.score-interval-seconds", 10) * 20L;
        if (interval > 0 && ticks > 0 && ticks % interval == 0 && meter.owner() != null) {
            String h = meter.owner();
            if (awardTeam("hold:" + ticks, h, scaled(h, cfg.getInt("points.hold", 10)),
                    PointAward.Reason.OBJECTIVE, "hold")) {
                gainEnergy(h, cfg.getDouble("energy.per-hold", 10));
                for (UUID m : membersOf(h)) if (isActive(m)) contribution.merge(m, 1, Integer::sum);
            }
        }

        // lockdown: keep the other teams out of the closed areas
        if (lockdownBy != null && ticks < lockdownUntil && ticks % 10 == 0) enforceLockdown();
        else if (lockdownBy != null && ticks >= lockdownUntil) { lockdownBy = null; tellKey("crown.lockdown-over"); }
        if (bountyBy != null && ticks >= bountyUntil) { bountyBy = bountyTarget = null; }

        if (ticks % 10 == 0) { drawZone(); }
        long left = Math.max(0, durationTicks - ticks) / 20;
        String holder = meter.owner() != null ? teamName(meter.owner()) : "§7niemand";
        bar("§6§lCROWN CONTROL §8| §e" + mmss(left) + " §8| §7kroon: " + holder,
                1.0 - (double) ticks / Math.max(1, durationTicks), left <= 20 ? BarColor.RED : BarColor.YELLOW);
        if (ticks >= durationTicks) end();
    }

    private void onCaptured(CaptureMeter.Change c, List<UUID> present) {
        var cfg = gamePlugin.getConfig();
        int n = ++changes;
        String team = c.owner();
        awardTeam("cap:" + n, team, scaled(team, cfg.getInt("points.capture", 25)), PointAward.Reason.OBJECTIVE, "capture");
        gainEnergy(team, cfg.getDouble("energy.per-capture", 20));
        if (c.previous() != null) {
            awardTeam("take:" + n, team, scaled(team, cfg.getInt("points.takeover", 15)), PointAward.Reason.BONUS, "takeover");
            gainEnergy(team, cfg.getDouble("energy.per-takeover", 10));
        }
        for (UUID id : present) contribution.merge(id, 3, Integer::sum);
        lockUntil = ticks + cfg.getInt("game.lock-seconds", 20) * 20L;
        int neutral = cfg.getInt("game.neutral-after-seconds", 0);
        neutralAt = neutral > 0 ? ticks + neutral * 20L : 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(p.getUniqueId())) continue;
            p.sendMessage(api.tr(p.getUniqueId(), c.previous() != null ? "crown.takeover" : "crown.captured",
                    teamName(team), c.previous() != null ? teamName(c.previous()) : ""));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
        }
    }

    /** Double Down multiplies the holder's objective rewards (never the activation itself). */
    private int scaled(String team, int base) {
        if (doubleUntil.getOrDefault(team, 0L) > ticks)
            return (int) Math.round(base * gamePlugin.getConfig().getDouble("abilities.double-down.multiplier", 2.0));
        return base;
    }

    private void gainEnergy(String team, double amount) {
        TeamEnergy e = energy.get(team);
        if (e != null) e.add(amount);
    }

    // ── Abilities ─────────────────────────────────────────────────────────────

    public static final String OK = "ok";

    /** Tries to activate an ability for the player's team; returns {@link #OK} or the lang key of the refusal. */
    public String activate(Player p, Ability a) {
        if (!live || !getState().isRunning()) return "crown.err.not-running";
        UUID id = p.getUniqueId();
        if (!isActive(id)) return "crown.err.not-active";
        String team = teamId(id);
        var cfg = gamePlugin.getConfig();
        String base = "abilities." + a.key + ".";
        if (!team.equals(meter.owner())) return "crown.err.no-crown";
        if (!cooldowns.ready(team + ":" + a.key, ticks)) return "crown.err.cooldown";
        TeamEnergy e = energy.get(team);
        double cost = cfg.getDouble(base + "cost", 20);
        if (e == null || e.value() + 1e-9 < cost) return "crown.err.energy";

        // checks that must pass BEFORE anything is paid
        if (a == Ability.LOCKDOWN && arena.regions("lockdown").isEmpty()) return "crown.err.no-lockdown-zone";
        if (a == Ability.LOCKDOWN && lockdownBy != null && ticks < lockdownUntil) return "crown.err.already-active";
        if (a == Ability.DOUBLE_DOWN && doubleUntil.getOrDefault(team, 0L) > ticks) return "crown.err.already-active";
        String bountyTargetTeam = null;
        if (a == Ability.BOUNTY) {
            if (bountyBy != null && ticks < bountyUntil) return "crown.err.already-active";
            bountyTargetTeam = pickBountyTarget(team);
            if (bountyTargetTeam == null) return "crown.err.no-target";
        }

        if (!e.trySpend(cost)) return "crown.err.energy";          // atomic: nothing is taken on failure
        long dur = cfg.getInt(base + "seconds", 10) * 20L;
        cooldowns.start(team + ":" + a.key, ticks, cfg.getInt(base + "cooldown", 20) * 20L);

        switch (a) {
            case DOUBLE_DOWN -> doubleUntil.put(team, ticks + dur);
            case LOCKDOWN -> { lockdownBy = team; lockdownUntil = ticks + dur; }
            case OVERDRIVE -> {
                overdriveUntil.put(team, ticks + dur);
                int lvl = Math.max(1, cfg.getInt(base + "speed-level", 2)) - 1;
                effect(team, PotionEffectType.SPEED, dur, lvl);
            }
            case BOUNTY -> { bountyBy = team; bountyTarget = bountyTargetTeam; bountyUntil = ticks + dur; }
            case FORTIFY -> {
                fortifyUntil.put(team, ticks + dur);
                effect(team, PotionEffectType.RESISTANCE, dur, 0);
            }
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(v.getUniqueId())) continue;
            v.sendMessage(api.tr(v.getUniqueId(), "crown.activated", teamName(team),
                    api.tr(v.getUniqueId(), "crown.ability." + a.key + ".name"),
                    a == Ability.BOUNTY ? teamName(bountyTargetTeam) : ""));
        }
        for (UUID m : membersOf(team)) {
            Player mp = Bukkit.getPlayer(m);
            if (mp != null) mp.playSound(mp.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        }
        return OK;
    }

    private String pickBountyTarget(String own) {
        String best = null;
        int bestPts = -1;
        for (String t : activeTeams()) {
            if (t.equals(own)) continue;
            boolean anyone = false;
            for (UUID m : membersOf(t)) if (isActive(m)) { anyone = true; break; }
            if (!anyone) continue;
            int pts = book.team(t);
            if (pts > bestPts) { bestPts = pts; best = t; }
        }
        return best;
    }

    private void effect(String team, PotionEffectType type, long ticks, int amplifier) {
        for (UUID m : membersOf(team)) {
            Player p = Bukkit.getPlayer(m);
            if (p != null && isActive(m)) p.addPotionEffect(new PotionEffect(type, (int) ticks, amplifier, false, true, true));
        }
    }

    private void enforceLockdown() {
        var zones = arena.regions("lockdown").values();
        for (UUID id : players) {
            if (!isActive(id) || teamId(id).equals(lockdownBy)) continue;
            Player p = Bukkit.getPlayer(id);
            Location l = p.getLocation();
            for (Region r : zones) {
                if (r.contains(l.getX(), l.getY(), l.getZ())) {
                    p.teleport(respawnLocation(p));
                    actionBar(p, api.tr(id, "crown.locked-out"));
                    p.playSound(l, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
                    break;
                }
            }
        }
    }

    @Override
    protected void onPlayerDown(Player victim, UUID killer) {
        if (bountyBy == null || ticks >= bountyUntil || killer == null) return;
        if (teamId(victim.getUniqueId()).equals(bountyTarget) && teamId(killer).equals(bountyBy)) {
            int bonus = gamePlugin.getConfig().getInt("abilities.bounty.bonus", 15);
            if (award("bounty:" + victim.getUniqueId() + ":" + ticks, killer, bonus, PointAward.Reason.BONUS, "bounty")) {
                contribution.merge(killer, 2, Integer::sum);
                Player k = Bukkit.getPlayer(killer);
                if (k != null) actionBar(k, api.tr(killer, "crown.bounty-paid", bonus));
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

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

    public void openMenu(Player p) {
        new AbilityGui(this, p).open(p);
    }

    /** State shown in the menu for one ability: {@code OK} when it can be used right now. */
    String abilityState(Player p, Ability a) {
        String team = teamId(p.getUniqueId());
        var cfg = gamePlugin.getConfig();
        if (!team.equals(meter.owner())) return "crown.err.no-crown";
        if (!cooldowns.ready(team + ":" + a.key, ticks)) return "crown.err.cooldown";
        TeamEnergy e = energy.get(team);
        if (e == null || e.value() + 1e-9 < cfg.getDouble("abilities." + a.key + ".cost", 20)) return "crown.err.energy";
        if (a == Ability.LOCKDOWN && arena.regions("lockdown").isEmpty()) return "crown.err.no-lockdown-zone";
        return OK;
    }

    double cost(Ability a) { return gamePlugin.getConfig().getDouble("abilities." + a.key + ".cost", 20); }
    long cooldownSeconds(Player p, Ability a) {
        return cooldowns.remaining(teamId(p.getUniqueId()) + ":" + a.key, ticks) / 20;
    }
    double energyOf(Player p) {
        TeamEnergy e = energy.get(teamId(p.getUniqueId()));
        return e != null ? e.value() : 0;
    }
    double capacity() { return gamePlugin.getConfig().getDouble("energy.capacity", 100); }

    // ── Visuals / HUD ─────────────────────────────────────────────────────────

    private void ensureCrown() {
        if (crownZone == null || arena.world() == null) return;
        if (crown != null && crown.isValid()) {
            String owner = meter != null ? meter.owner() : null;
            if (owner != null) crown.setGlowColorOverride(colorOf(teamColor(owner)));
            return;
        }
        Location at = new Location(arena.world(), crownZone.centerX(), crownZone.minY() + 2.2, crownZone.centerZ());
        crown = arena.world().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.GOLDEN_HELMET));
            d.setBillboard(Display.Billboard.CENTER);
            d.setGlowing(true);
            d.setPersistent(false);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.Quaternionf(), new org.joml.Vector3f(1.8f, 1.8f, 1.8f), new org.joml.Quaternionf()));
        });
    }

    private void drawZone() {
        World w = arena.world();
        if (w == null) return;
        Region r = crownZone;
        String owner = meter.owner() != null ? meter.owner() : meter.capturer();
        Color c = owner != null ? colorOf(teamColor(owner)) : Color.YELLOW;
        if (meter.status() == CaptureStatus.CONTESTED) c = Color.WHITE;
        Particle.DustOptions dust = new Particle.DustOptions(c, 1.5f);
        double cx = r.centerX(), cz = r.centerZ(), radius = Math.max(r.sizeX(), r.sizeZ()) / 2;
        double y = r.minY() + 0.4;
        for (int i = 0; i < 32; i++) {
            double a = 2 * Math.PI * i / 32;
            w.spawnParticle(Particle.DUST, cx + Math.cos(a) * radius, y, cz + Math.sin(a) * radius, 1, 0, 0, 0, 0, dust);
        }
        if (ticks < lockUntil) w.spawnParticle(Particle.END_ROD, cx, r.minY() + 3.2, cz, 3, 0.3, 0.3, 0.3, 0.01);
    }

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        String team = teamId(id);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "crown.sb.time", mmss(Math.max(0, durationTicks - ticks) / 20)));
        l.add(api.tr(id, "crown.sb.holder", meter.owner() != null ? teamName(meter.owner()) : "§7-"));
        if (ticks < lockUntil) l.add(api.tr(id, "crown.sb.locked", Math.max(0, (lockUntil - ticks) / 20)));
        else if (meter.capturer() != null) l.add(api.tr(id, "crown.sb.capturing", (int) Math.round(meter.fraction() * 100)));
        l.add("");
        l.add(api.tr(id, "crown.sb.team", book.team(team)));
        l.add(api.tr(id, "crown.sb.energy", (int) energyOf(viewer), (int) capacity()));
        if (doubleUntil.getOrDefault(team, 0L) > ticks) l.add(api.tr(id, "crown.sb.double", (doubleUntil.get(team) - ticks) / 20));
        if (bountyBy != null && ticks < bountyUntil) l.add(api.tr(id, "crown.sb.bounty", teamName(bountyTarget)));
        l.add(api.tr(id, "crown.sb.you", contribution.getOrDefault(id, 0)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        String holder = meter.owner();
        if (holder != null) {
            awardTeam("end:" + holder, holder, gamePlugin.getConfig().getInt("points.end-bonus", 50),
                    PointAward.Reason.BONUS, "end-bonus");
        }
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
            v.sendMessage(api.tr(vid, "crown.results"));
            int i = 1;
            for (String t : ranked)
                v.sendMessage("  §7#" + (i++) + " " + teamName(t) + " §8— §e" + book.team(t) + " ptn"
                        + (t.equals(holder) ? " §6♛" : ""));
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
        if (crown != null && crown.isValid()) crown.remove();
        crown = null;
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.SPEED);
            p.removePotionEffect(PotionEffectType.RESISTANCE);
        }
        energy.clear();
        lockdownBy = null;
        bountyBy = bountyTarget = null;
    }
}
