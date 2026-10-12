package nl.kmc.knockout;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.arcade.ArcadeGameManager;
import nl.kmc.game.api.arcade.ArcadeLogic.Region;
import nl.kmc.game.api.arcade.ArenaConfig;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Knockout Kings: a stick that knocks, a platform that ends. Three rounds of 90 s; an elimination is a fall
 * from the platform, credited to the last valid attacker. Every score has a per-life key, so a fall is paid
 * once, and a farm limit stops the same pair from trading eliminations.
 */
public final class KnockoutKingsManager extends ArcadeGameManager {

    private enum Phase { ROUND, INTERMISSION }

    private static final class RoundStats { int kills, deaths, assists; }

    private final KnockoutKingsPlugin plugin;

    private Phase phase = Phase.INTERMISSION;
    private int round;
    private int totalRounds;
    private long phaseStartTick, phaseEndTick;
    private String modifier = "NONE";
    private Region basePlatform;
    private Region currentPlatform;
    private double killY;

    private final Map<UUID, RoundStats> stats = new HashMap<>();
    private final Map<UUID, Integer> life = new HashMap<>();
    private final Map<UUID, Long> lastHitTick = new HashMap<>();
    /** victim -> (attacker -> tick of last hit), for assists. */
    private final Map<UUID, Map<UUID, Long>> hitLog = new HashMap<>();
    /** "attacker>victim" -> ticks of paid eliminations (farm check). */
    private final Map<String, List<Long>> paidKills = new HashMap<>();
    private final Map<Location, BlockData> removedBlocks = new LinkedHashMap<>();
    private final Set<UUID> survive30Paid = new HashSet<>();
    private boolean survive30Done;
    private long nextVanishTick;
    private final Random rnd = new Random();

    public KnockoutKingsManager(KnockoutKingsPlugin plugin, GameRegistration reg, StatisticsService stats, ArenaConfig arena) {
        super(plugin, reg, stats, arena);
        this.plugin = plugin;
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    @Override protected String titleColored() { return "§c§lKNOCKOUT KINGS"; }
    @Override protected String introSubKey() { return "@knockout.intro-sub"; }
    @Override protected List<String> tutorialKeys() {
        return List.of("@knockout.tut.1", "@knockout.tut.2", "@knockout.tut.3", "@knockout.tut.4");
    }

    @Override
    protected List<String> arenaProblems() {
        List<String> p = new ArrayList<>();
        if (arena.world() == null) p.add("geen arena-wereld (/knockout world)");
        if (arena.region("platform") == null) p.add("geen platform (/knockout pos1 + pos2 + platform)");
        if (arena.locs("spawns").isEmpty()) p.add("geen startposities (/knockout spawn add)");
        return p;
    }

    @Override protected List<Location> spawnPoints() { return arena.locs("spawns"); }

    @Override
    protected Location flyoverCenter() {
        Region r = arena.region("platform");
        World w = arena.world();
        return r == null || w == null ? null : new Location(w, r.centerX(), r.maxY(), r.centerZ());
    }

    @Override protected Combat combat() { return Combat.NONE; }   // hits are handled by onPlayerHit
    @Override protected int respawnSeconds() {
        return suddenDeath() ? 100000 : gamePlugin.getConfig().getInt("combat.respawn-seconds", 3);
    }

    @Override
    protected Location respawnLocation(Player p) {
        List<Location> rs = arena.locs("respawns");
        if (rs.isEmpty()) rs = arena.locs("spawns");
        return rs.get(rnd.nextInt(rs.size()));
    }

    @Override
    protected void prepareGame() {
        stats.clear();
        life.clear();
        lastHitTick.clear();
        hitLog.clear();
        paidKills.clear();
        removedBlocks.clear();
        survive30Paid.clear();
        basePlatform = arena.region("platform");
        currentPlatform = basePlatform;
        double def = basePlatform != null ? basePlatform.minY() - 8 : 0;
        double cfgKill = gamePlugin.getConfig().getDouble("arena.kill-y", -1000);
        killY = cfgKill > -999 ? cfgKill : def;
        totalRounds = Math.max(1, gamePlugin.getConfig().getInt("game.rounds", 3));
        round = 0;
        for (UUID id : players) { stats.put(id, new RoundStats()); life.put(id, 0); }
    }

    @Override
    protected void beginPlay() {
        startRound(1);
    }

    // ── Rounds ────────────────────────────────────────────────────────────────

    private void startRound(int n) {
        round = n;
        phase = Phase.ROUND;
        var cfg = gamePlugin.getConfig();
        phaseStartTick = ticks;
        phaseEndTick = ticks + cfg.getInt("game.round-seconds", 90) * 20L;
        survive30Done = false;
        survive30Paid.clear();
        hitLog.clear();
        lastHitTick.clear();
        currentPlatform = basePlatform;
        List<String> mods = cfg.getStringList("game.modifiers");
        modifier = mods.isEmpty() ? "NONE" : mods.get((n - 1) % mods.size()).toUpperCase(Locale.ROOT);
        nextVanishTick = ticks + cfg.getInt("game.vanishing.interval-seconds", 6) * 20L;

        List<Location> spawns = arena.locs("spawns");
        int i = 0;
        for (UUID id : players) {
            if (!isParticipant(id)) continue;
            RoundStats rs = new RoundStats();
            stats.put(id, rs);
            life.merge(id, 1, Integer::sum);
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            respawnNowQuiet(p, spawns.get(i++ % spawns.size()));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isParticipant(p.getUniqueId())) continue;
            UUID id = p.getUniqueId();
            p.sendTitle(api.tr(id, "knockout.round.title", n, totalRounds),
                    api.tr(id, "knockout.mod." + modifier.toLowerCase(Locale.ROOT)), 5, 50, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
        }
        applyModifierStart();
    }

    /** Puts a player on a start position with weapon and protection (no respawn bookkeeping). */
    private void respawnNowQuiet(Player p, Location at) {
        p.setGameMode(GameMode.ADVENTURE);
        GamePlayerUtil.safeTeleport(p, at);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        giveWeapon(p);
        protect(p.getUniqueId(), gamePlugin.getConfig().getInt("combat.spawn-protection-seconds", 3));
        applyModifierTo(p);
    }

    @Override
    protected void onRespawn(Player p) {
        giveWeapon(p);
        applyModifierTo(p);
    }

    private void endRound() {
        phase = Phase.INTERMISSION;
        var cfg = gamePlugin.getConfig();
        clearModifier();

        // round winner(s): most eliminations, then fewest deaths
        List<UUID> in = new ArrayList<>();
        for (UUID id : players) if (isParticipant(id)) in.add(id);
        int bestKills = -1, bestDeaths = Integer.MAX_VALUE;
        for (UUID id : in) {
            RoundStats s = stats.getOrDefault(id, new RoundStats());
            if (s.kills > bestKills || (s.kills == bestKills && s.deaths < bestDeaths)) {
                bestKills = s.kills;
                bestDeaths = s.deaths;
            }
        }
        List<UUID> winners = new ArrayList<>();
        for (UUID id : in) {
            RoundStats s = stats.getOrDefault(id, new RoundStats());
            if (s.kills == bestKills && s.deaths == bestDeaths && bestKills >= 0) winners.add(id);
        }
        int bonus = cfg.getInt("points.round-winner", 40);
        boolean share = "SHARE".equalsIgnoreCase(cfg.getString("game.tie-mode", "ALL")) && winners.size() > 1;
        int each = share ? Math.max(1, bonus / winners.size()) : bonus;
        for (UUID id : winners) award("rw:" + round + ":" + id, id, each, PointAward.Reason.OBJECTIVE, "round-winner");

        // perfect round: played the whole round without going down
        int perfect = cfg.getInt("points.perfect-round", 15);
        for (UUID id : in) {
            RoundStats s = stats.getOrDefault(id, new RoundStats());
            if (s.deaths == 0) award("perfect:" + round + ":" + id, id, perfect, PointAward.Reason.BONUS, "perfect-round");
        }

        // everybody back in the game for the next round / the end
        for (UUID id : in) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) { p.getInventory().clear(); respawnNow(p); }
        }

        String names = winners.isEmpty() ? "-" : String.join(", ", winners.stream().map(this::nameOf).toList());
        for (Player v : Bukkit.getOnlinePlayers())
            v.sendMessage(api.tr(v.getUniqueId(), "knockout.round.over", round, names, bestKills));

        if (round >= totalRounds) { end(); return; }
        phaseStartTick = ticks;
        phaseEndTick = ticks + cfg.getInt("game.intermission-seconds", 8) * 20L;
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    @Override
    protected void playTick() {
        if (phase == Phase.INTERMISSION) {
            long left = Math.max(0, phaseEndTick - ticks) / 20;
            bar("§c§lKNOCKOUT KINGS §8| §7Ronde " + (round + 1) + " start over §e" + left + "s", 1, BarColor.YELLOW);
            if (ticks >= phaseEndTick) startRound(round + 1);
            return;
        }

        long left = Math.max(0, phaseEndTick - ticks);
        // shrink + vanish + survive bonus
        if ("SHRINKING_ARENA".equals(modifier)) updateShrink();
        if ("VANISHING_PLATFORMS".equals(modifier)) vanishStep();
        if (!survive30Done && left <= 30 * 20L) {
            survive30Done = true;
            int pts = gamePlugin.getConfig().getInt("points.survive-until-last-30", 20);
            for (UUID id : players) {
                RoundStats s = stats.get(id);
                if (isActive(id) && s != null && s.deaths == 0)
                    award("s30:" + round + ":" + id, id, pts, PointAward.Reason.SURVIVAL_BONUS, "survive");
            }
        }

        // falls
        Region plat = currentPlatform;
        List<Player> out = new ArrayList<>();
        for (UUID id : players) {
            if (!isActive(id)) continue;
            Player p = Bukkit.getPlayer(id);
            Location l = p.getLocation();
            boolean outside = !plat.containsXZ(l.getX(), l.getZ()) && l.getY() < plat.maxY() + 0.5;
            if (l.getY() < killY || (outside && !isProtected(id))) out.add(p);
        }
        for (Player p : out) eliminate(p);

        if (ticks % 10 == 0 && "SHRINKING_ARENA".equals(modifier)) drawBorder();
        long secs = left / 20;
        bar("§c§lKNOCKOUT KINGS §8| §7Ronde §f" + round + "/" + totalRounds + " §8| §e" + mmss(secs)
                        + " §8| §7" + api.tr(players.isEmpty() ? UUID.randomUUID() : players.get(0),
                        "knockout.mod." + modifier.toLowerCase(Locale.ROOT)),
                (double) left / Math.max(1, phaseEndTick - phaseStartTick), secs <= 15 ? BarColor.RED : BarColor.BLUE);
        if (ticks >= phaseEndTick) endRound();
    }

    private boolean suddenDeath() {
        if (!live || phase != Phase.ROUND) return false;
        int sd = gamePlugin.getConfig().getInt("game.sudden-death-seconds", 15);
        return sd > 0 && phaseEndTick - ticks <= sd * 20L;
    }

    // ── Hits, eliminations ────────────────────────────────────────────────────

    @Override
    protected boolean onPlayerHit(Player attacker, Player victim, EntityDamageByEntityEvent e) {
        e.setCancelled(true);
        if (phase != Phase.ROUND || !live) return true;
        UUID a = attacker.getUniqueId(), v = victim.getUniqueId();
        if (!isActive(a) || !isActive(v) || isProtected(v) || a.equals(v)) return true;
        if (!friendlyFire() && teamId(a).equals(teamId(v))) return true;
        if (isProtected(a)) { /* attacking ends your own protection */ protect(a, 0); }

        var cfg = gamePlugin.getConfig();
        long cd = cfg.getInt("game.hit-cooldown-ticks", 8);
        if (ticks - lastHitTick.getOrDefault(v, -1000L) < cd) return true;
        lastHitTick.put(v, ticks);

        double kb = cfg.getDouble("game.knockback", 1.15);
        if ("POWER_KNOCKBACK".equals(modifier)) kb *= cfg.getDouble("game.power-knockback-multiplier", 1.6);
        if (attacker.isSprinting()) kb *= 1.2;
        Vector dir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = attacker.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(kb);
        dir.setY(cfg.getDouble("game.knockback-vertical", 0.42));
        victim.setVelocity(dir);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.1f);
        victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.1);

        noteAttack(a, v);
        hitLog.computeIfAbsent(v, k -> new LinkedHashMap<>()).put(a, ticks);
        return true;
    }

    /** A player fell off: credit the last valid attacker once and send the player to the respawn flow. */
    private void eliminate(Player victim) {
        UUID v = victim.getUniqueId();
        var cfg = gamePlugin.getConfig();
        int window = cfg.getInt("game.attribution-seconds", 6);
        UUID killer = recentAttacker(v, window);
        if (killer != null && (!isParticipant(killer) || killer.equals(v))) killer = null;
        String key = round + ":" + v + ":" + life.getOrDefault(v, 0);

        RoundStats vs = stats.computeIfAbsent(v, k -> new RoundStats());
        vs.deaths++;

        if (killer != null) {
            long now = ticks;
            long farmWindow = cfg.getInt("game.farm-window-seconds", 20) * 20L;
            int farmMax = Math.max(1, cfg.getInt("game.farm-max-kills", 1));
            String pair = killer + ">" + v;
            List<Long> paid = paidKills.computeIfAbsent(pair, k -> new ArrayList<>());
            paid.removeIf(t -> now - t > farmWindow);
            if (paid.size() >= farmMax) {
                log.warning("[KnockoutKings] farm limit: " + nameOf(killer) + " > " + nameOf(v)
                        + " (" + paid.size() + " paid within " + (farmWindow / 20) + "s) — no points");
            } else if (award("elim:" + key, killer, cfg.getInt("points.elimination", 15),
                    PointAward.Reason.KILL, "elimination")) {
                paid.add(now);
                stats.computeIfAbsent(killer, k -> new RoundStats()).kills++;
                Player kp = Bukkit.getPlayer(killer);
                if (kp != null) actionBar(kp, api.tr(killer, "knockout.kill", victim.getName()));
                try { api.games().announceElimination(v, killer, registration.getId()); } catch (Throwable ignored) { }
            }
            // assists: everybody else who hit the victim inside the assist window, once each
            int aw = cfg.getInt("game.assist-window-seconds", 6);
            Map<UUID, Long> hits = hitLog.getOrDefault(v, Map.of());
            for (var en : hits.entrySet()) {
                UUID helper = en.getKey();
                if (helper.equals(killer) || helper.equals(v) || !isParticipant(helper)) continue;
                if (ticks - en.getValue() > aw * 20L) continue;
                if (award("assist:" + key + ":" + helper, helper, cfg.getInt("points.assist", 5),
                        PointAward.Reason.ASSIST, "assist")) {
                    stats.computeIfAbsent(helper, k -> new RoundStats()).assists++;
                }
            }
        }
        hitLog.remove(v);
        down(victim, killer);
        life.merge(v, 1, Integer::sum);
    }

    // ── Weapon ────────────────────────────────────────────────────────────────

    private void giveWeapon(Player p) {
        p.getInventory().clear();
        ItemStack stick = new ItemStack(Material.STICK);
        ItemMeta m = stick.getItemMeta();
        m.setDisplayName(api.tr(p.getUniqueId(), "knockout.weapon"));
        m.addEnchant(Enchantment.KNOCKBACK, 2, true);
        m.setUnbreakable(true);
        stick.setItemMeta(m);
        p.getInventory().setItem(0, stick);
    }

    // ── Modifiers ─────────────────────────────────────────────────────────────

    private void applyModifierStart() {
        for (Player p : activePlayers()) applyModifierTo(p);
    }

    private void applyModifierTo(Player p) {
        if (!"LOW_GRAVITY".equals(modifier) || phase != Phase.ROUND) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 2, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, Integer.MAX_VALUE, 0, false, false, false));
    }

    private void clearModifier() {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.removePotionEffect(PotionEffectType.JUMP_BOOST);
            p.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
        restoreBlocks();
        currentPlatform = basePlatform;
    }

    private void updateShrink() {
        double total = Math.max(1, phaseEndTick - phaseStartTick);
        double progress = Math.max(0, Math.min(1, (ticks - phaseStartTick) / total));
        double finalFraction = gamePlugin.getConfig().getDouble("game.shrinking.final-fraction", 0.4);
        double shrink = (1 - finalFraction) * progress;
        currentPlatform = basePlatform.shrunk(shrink, gamePlugin.getConfig().getDouble("game.shrinking.min-size", 8));
    }

    private void drawBorder() {
        World w = arena.world();
        if (w == null) return;
        Region r = currentPlatform;
        double y = r.minY() + 1.2;
        for (double x = r.minX(); x <= r.maxX(); x += 2) {
            w.spawnParticle(Particle.FLAME, x, y, r.minZ(), 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.FLAME, x, y, r.maxZ(), 1, 0, 0, 0, 0);
        }
        for (double z = r.minZ(); z <= r.maxZ(); z += 2) {
            w.spawnParticle(Particle.FLAME, r.minX(), y, z, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.FLAME, r.maxX(), y, z, 1, 0, 0, 0, 0);
        }
    }

    private void vanishStep() {
        var cfg = gamePlugin.getConfig();
        if (ticks < nextVanishTick) return;
        World w = arena.world();
        if (w == null) return;
        nextVanishTick = ticks + cfg.getInt("game.vanishing.interval-seconds", 6) * 20L;
        int size = Math.max(1, cfg.getInt("game.vanishing.size", 3));
        int cx = (int) (basePlatform.minX() + rnd.nextDouble() * basePlatform.sizeX());
        int cz = (int) (basePlatform.minZ() + rnd.nextDouble() * basePlatform.sizeZ());
        List<Block> blocks = new ArrayList<>();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!basePlatform.containsXZ(x + 0.5, z + 0.5)) continue;
                for (int y = (int) basePlatform.maxY() - 1; y >= (int) basePlatform.minY(); y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) { blocks.add(b); break; }
                }
            }
        }
        if (blocks.isEmpty()) return;
        for (Block b : blocks) w.spawnParticle(Particle.DUST, b.getLocation().add(0.5, 1.1, 0.5), 6, 0.3, 0.05, 0.3, 0,
                new Particle.DustOptions(Color.RED, 1.5f));
        long warn = cfg.getInt("game.vanishing.warn-seconds", 2) * 20L;
        Bukkit.getScheduler().runTaskLater(gamePlugin, () -> {
            if (!live || phase != Phase.ROUND) return;
            for (Block b : blocks) {
                if (b.getType().isAir()) continue;
                removedBlocks.putIfAbsent(b.getLocation(), b.getBlockData());
                b.setType(Material.AIR, false);
            }
        }, warn);
    }

    private void restoreBlocks() {
        for (var en : removedBlocks.entrySet()) {
            Location l = en.getKey();
            if (l.getWorld() != null) l.getBlock().setBlockData(en.getValue(), false);
        }
        removedBlocks.clear();
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    @Override
    protected List<String> sidebar(Player viewer) {
        UUID id = viewer.getUniqueId();
        RoundStats s = stats.getOrDefault(id, new RoundStats());
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "knockout.sb.round", Math.max(1, round), totalRounds));
        l.add(api.tr(id, "knockout.sb.time", mmss(Math.max(0, phaseEndTick - ticks) / 20)));
        l.add(api.tr(id, "knockout.sb.mod", api.tr(id, "knockout.mod." + modifier.toLowerCase(Locale.ROOT))));
        l.add("");
        l.add(api.tr(id, "knockout.sb.kills", s.kills));
        l.add(api.tr(id, "knockout.sb.deaths", s.deaths));
        l.add(api.tr(id, "knockout.sb.points", book.player(id)));
        return l;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Override
    protected Result finishGame() {
        clearModifier();
        List<UUID> order = book.rankPlayers(players);
        awardPlayerPlacements(order);
        List<String> teams = book.rankTeams(activeTeams());
        awardTeamPlacements(teams);

        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "knockout.results"));
            for (int i = 0; i < Math.min(8, order.size()); i++) {
                UUID id = order.get(i);
                v.sendMessage("  §7#" + (i + 1) + " §f" + nameOf(id) + " §8— §e" + book.player(id) + " ptn");
            }
        }
        UUID top = order.isEmpty() ? null : order.get(0);
        String winner = !teams.isEmpty() && !teams.get(0).startsWith("solo_") ? teamName(teams.get(0))
                : top != null ? nameOf(top) : "-";
        return new Result(winner, top, top != null ? nameOf(top) : null, order);
    }

    @Override
    protected void cleanupGame() {
        restoreBlocks();
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.removePotionEffect(PotionEffectType.JUMP_BOOST);
                p.removePotionEffect(PotionEffectType.SLOW_FALLING);
            }
        }
        stats.clear();
    }
}
