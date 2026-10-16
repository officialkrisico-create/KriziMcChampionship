package nl.kmc.inferno.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.game.api.ArenaValidator;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.PlayerGameState;
import nl.kmc.game.api.StandardStartFlow;
import nl.kmc.game.api.ValidationResult;
import nl.kmc.inferno.RisingInfernoPlugin;
import nl.kmc.inferno.model.Box;
import nl.kmc.inferno.model.LavaModel;
import nl.kmc.kmccore.presentation.ChatLayout;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Rising Inferno — everyone starts at the bottom of a tower and has to reach the finish at the top while the
 * lava rises. Two kinds of routes: a long safe one, and a short risky one with bonus orbs. The lava is a
 * single scaled block display (no real lava blocks, so there is nothing to clean up afterwards); a player
 * whose feet are below its surface is out.
 *
 * <p>Ranking: finishers by arrival, then survivors by the height they reached, then the eliminated, the
 * latest eliminated first. Points: placement + a finish bonus + a bonus per orb.
 */
public final class RisingInfernoGameManagerV2 extends BaseGameManager {

    private enum Cause { BURNED, FELL, QUIT }

    private static final class PState {
        final UUID id;
        final String name;
        boolean alive = true;
        boolean finished;
        long finishMs;
        double maxY = Double.NEGATIVE_INFINITY;
        final Set<Integer> orbs = new HashSet<>();
        PState(UUID id, String name) { this.id = id; this.name = name; }
    }

    private final RisingInfernoPlugin plugin;
    private final InfernoArena arena;

    private final Map<UUID, PState> players = new LinkedHashMap<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final List<UUID> eliminatedOrder = new ArrayList<>();

    private StandardStartFlow startFlow;
    private BossBar bossBar;
    private BukkitTask loop;
    /** The lava surface: a grid of stretched magma blocks (a display entity cannot show a fluid). */
    private final List<BlockDisplay> lavaTiles = new ArrayList<>();
    private static final double TILE = 8;
    private final List<ItemDisplay> orbDisplays = new ArrayList<>();

    private LavaModel model;
    private volatile boolean climbing;
    private long climbStartMs;
    private int tickCount;
    private double lavaY;

    public RisingInfernoGameManagerV2(RisingInfernoPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.arena = plugin.getArena();
    }

    // ── Prepare / start ───────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        plugin.reloadConfig();
        players.clear();
        finishOrder.clear();
        eliminatedOrder.clear();
        climbing = false;
        removeLavaAndOrbs();

        if (!arena.isReady()) {
            log.severe("[RisingInferno] Arena not ready: " + arena.problems());
            end();
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Location> spawns = arena.getSpawns();
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            players.put(p.getUniqueId(), new PState(p.getUniqueId(), p.getName()));
            GamePlayerUtil.resetPlayer(p);
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(spawns.get(i % spawns.size()));
        }

        bossBar = Bukkit.createBossBar("§c§l🔥 Rising Inferno", BarColor.RED, BarStyle.SOLID);
        online.forEach(bossBar::addPlayer);

        List<Player> parts = new ArrayList<>(online);
        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§c§lRISING INFERNO"; }
                    @Override public String introSubtitle() { return "@inferno.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@inferno.tut.1", "@inferno.tut.2", "@inferno.tut.3", "@inferno.tut.4");
                    }
                    @Override public Location flyoverCenter() { return arena.centre(); }
                    @Override public void onFinished() { beginClimb(); }
                });
        startFlow.prepareAndFreeze();
    }

    @Override protected void onCountdownStart() { /* presentation runs from onGameStart */ }

    @Override protected void onGameStart() { if (startFlow != null) startFlow.start(); }

    // ── The climb ─────────────────────────────────────────────────────────────

    private void beginClimb() {
        if (!getState().isRunning()) return;
        var cfg = plugin.getConfig();
        double baseY = arena.getLavaBaseY();
        double endY = arena.finishFloorY() + cfg.getDouble("game.overshoot", 3);
        int rise = arena.getRiseSeconds() > 0 ? arena.getRiseSeconds() : cfg.getInt("game.rise-seconds", 150);
        model = new LavaModel(baseY, endY, cfg.getDouble("game.lava-delay-seconds", 12), rise,
                cfg.getDouble("game.rise-exponent", 1.4));
        lavaY = baseY;
        climbStartMs = System.currentTimeMillis();
        tickCount = 0;
        climbing = true;

        spawnLava();
        spawnOrbs();

        for (PState ps : players.values()) {
            Player p = Bukkit.getPlayer(ps.id);
            if (p == null) continue;
            GamePlayerUtil.unfreezePlayer(p);
            p.sendTitle("§c§lKLIM!", api.tr(ps.id, "inferno.go-sub"), 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
        }
        if (bossBar != null) bossBar.setColor(BarColor.RED);
        loop = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private void tick() {
        if (!climbing || !getState().isRunning()) return;
        tickCount++;
        var cfg = plugin.getConfig();
        double elapsed = (System.currentTimeMillis() - climbStartMs) / 1000.0;
        lavaY = model.yAt(elapsed);
        moveLava();

        Box area = arena.getLavaArea();
        Box fin = arena.getFinish();
        double burnMargin = cfg.getDouble("game.burn-margin", 0.1);
        double voidY = arena.getLavaBaseY() - cfg.getDouble("game.void-depth", 25);

        List<PState> burned = new ArrayList<>();
        List<PState> fell = new ArrayList<>();
        List<PState> quit = new ArrayList<>();

        for (PState ps : players.values()) {
            if (!ps.alive || ps.finished) continue;
            Player p = Bukkit.getPlayer(ps.id);
            if (p == null || !p.isOnline()) { quit.add(ps); continue; }
            Location l = p.getLocation();
            double y = l.getY();
            if (y > ps.maxY) ps.maxY = y;

            if (fin.contains(l.getX(), y, l.getZ())) { finish(ps, p, elapsed); continue; }
            collectOrbs(ps, p);

            boolean over = area.containsXZ(l.getX(), l.getZ(), 4);
            if (over && y < lavaY - burnMargin) { burned.add(ps); continue; }
            if (y < voidY) { fell.add(ps); continue; }

            double gap = y - lavaY;
            if (over && gap < 5 && tickCount % 5 == 0) {
                p.sendActionBar(net.kyori.adventure.text.Component.text(
                        api.tr(ps.id, "inferno.warn", String.format(Locale.ROOT, "%.1f", Math.max(0, gap)))));
                if (gap < 2.5) p.playSound(l, Sound.BLOCK_LAVA_POP, 0.6f, 1.0f);
            }
        }

        eliminate(burned, Cause.BURNED);
        eliminate(fell, Cause.FELL);
        eliminate(quit, Cause.QUIT);

        if (tickCount % 5 == 0) lavaParticles();
        updateBossBar(elapsed);

        int maxSeconds = cfg.getInt("game.max-duration-seconds", 420);
        if (aliveCount() == 0 || elapsed >= maxSeconds) end();
    }

    private void finish(PState ps, Player p, double elapsed) {
        ps.finished = true;
        ps.finishMs = (long) (elapsed * 1000);
        finishOrder.add(ps.id);
        int place = finishOrder.size();

        int bonus = plugin.getConfig().getInt("points.finish", 30);
        if (bonus > 0) api.points().givePoints(ps.id, bonus, PointAward.Reason.OBJECTIVE, registration.getId());
        api.games().announceFinish(ps.id, place, players.size(), registration.getId());

        for (var type : new nl.kmc.core.event.GameObjectiveEvent.Type[] {
                nl.kmc.core.event.GameObjectiveEvent.Type.RACE_FINISHED,
                nl.kmc.core.event.GameObjectiveEvent.Type.RACE_FINISHED_FAST}) {
            try { new nl.kmc.core.event.GameObjectiveEvent(p, registration.getId(), type, ps.finishMs).callEvent(); }
            catch (Throwable t) { plugin.getLogger().warning("GameObjectiveEvent failed: " + t); }
        }
        p.sendTitle("§6§l#" + place, "§e" + fmt(ps.finishMs), 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        tellKey("inferno.finished", ps.name, place, fmt(ps.finishMs));
        p.setGameMode(GameMode.SPECTATOR);
    }

    private void eliminate(List<PState> victims, Cause cause) {
        if (victims.isEmpty()) return;
        for (PState ps : victims) {
            ps.alive = false;
            eliminatedOrder.add(ps.id);
            String key = switch (cause) {
                case BURNED -> "inferno.burned";
                case FELL -> "inferno.fell";
                case QUIT -> "inferno.quit";
            };
            tellKey(key, ps.name);
            Player p = Bukkit.getPlayer(ps.id);
            if (p == null) continue;
            p.getWorld().spawnParticle(Particle.LAVA, p.getLocation().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.1);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BURN, 1f, 0.8f);
            p.sendTitle(api.tr(ps.id, "inferno.out.title"), api.tr(ps.id, "inferno.out.sub", percentOf(ps)), 5, 50, 15);
            p.setGameMode(GameMode.SPECTATOR);
            Location spec = arena.spectatorSpawn();
            if (spec != null) p.teleport(spec);
        }
    }

    /** Called by the listener when a participant leaves. */
    public void handleQuit(UUID uuid) {
        PState ps = players.get(uuid);
        if (ps == null || !climbing || !ps.alive || ps.finished) return;
        eliminate(List.of(ps), Cause.QUIT);
        if (aliveCount() == 0) end();
    }

    // ── Orbs ──────────────────────────────────────────────────────────────────

    private void spawnOrbs() {
        World w = arena.getWorld();
        if (w == null) return;
        for (Location l : arena.getOrbs()) {
            ItemDisplay d = w.spawn(l, ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.NETHER_STAR));
                e.setBillboard(Display.Billboard.CENTER);
                e.setGlowing(true);
                e.setPersistent(false);
            });
            orbDisplays.add(d);
        }
    }

    private void collectOrbs(PState ps, Player p) {
        List<Location> orbs = arena.getOrbs();
        if (orbs.isEmpty()) return;
        Location body = p.getLocation().add(0, 1, 0);
        for (int i = 0; i < orbs.size(); i++) {
            if (ps.orbs.contains(i)) continue;
            Location o = orbs.get(i);
            if (o.getWorld() != body.getWorld() || o.distanceSquared(body) > 2.0 * 2.0) continue;
            ps.orbs.add(i);
            int pts = plugin.getConfig().getInt("points.orb", 10);
            if (pts > 0) api.points().givePoints(ps.id, pts, PointAward.Reason.BONUS, registration.getId());
            int secs = Math.max(1, plugin.getConfig().getInt("orbs.effect-seconds", 6));
            PotionEffectType type = switch (new Random().nextInt(3)) {
                case 0 -> PotionEffectType.SPEED;
                case 1 -> PotionEffectType.JUMP_BOOST;
                default -> PotionEffectType.SLOW_FALLING;
            };
            p.addPotionEffect(new PotionEffect(type, secs * 20, 1, false, true, true));
            p.spawnParticle(Particle.TOTEM_OF_UNDYING, o, 25, 0.3, 0.3, 0.3, 0.2);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            p.sendMessage(api.tr(ps.id, "inferno.orb", pts, ps.orbs.size(), orbs.size()));
            if (ps.orbs.size() == orbs.size()) {
                try {
                    new nl.kmc.core.event.GameObjectiveEvent(p, registration.getId(),
                            nl.kmc.core.event.GameObjectiveEvent.Type.ALL_BONUSES_COLLECTED).callEvent();
                } catch (Throwable t) { plugin.getLogger().warning("GameObjectiveEvent failed: " + t); }
            }
        }
    }

    // ── Lava visuals ──────────────────────────────────────────────────────────

    private static final double THICKNESS = 8;
    private static final double MARGIN = 3;

    private void spawnLava() {
        World w = arena.getWorld();
        Box a = arena.getLavaArea();
        if (w == null || a == null) return;
        lavaTiles.clear();
        double x0 = a.minX() - MARGIN, z0 = a.minZ() - MARGIN;
        double sx = a.sizeX() + 2 * MARGIN, sz = a.sizeZ() + 2 * MARGIN;
        // Lava itself cannot be shown by a block display (fluids have no model), so the surface is made of
        // magma blocks, stretched over tiles of 8 x 8 so the texture stays sharp.
        for (double dx = 0; dx < sx; dx += TILE) {
            for (double dz = 0; dz < sz; dz += TILE) {
                final float tw = (float) Math.min(TILE, sx - dx), td = (float) Math.min(TILE, sz - dz);
                Location l = new Location(w, x0 + dx, lavaY - THICKNESS, z0 + dz);
                BlockDisplay d = w.spawn(l, BlockDisplay.class, e -> {
                    e.setBlock(Material.MAGMA_BLOCK.createBlockData());
                    e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                            new Vector3f(tw, (float) THICKNESS, td), new AxisAngle4f()));
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setViewRange(8f);
                    e.setDisplayWidth(0f);
                    e.setDisplayHeight(0f);
                    e.setPersistent(false);
                    e.setTeleportDuration(2);
                });
                lavaTiles.add(d);
            }
        }
    }

    private void moveLava() {
        Box a = arena.getLavaArea();
        if (a == null || lavaTiles.isEmpty()) return;
        double x0 = a.minX() - MARGIN, z0 = a.minZ() - MARGIN;
        double sx = a.sizeX() + 2 * MARGIN, sz = a.sizeZ() + 2 * MARGIN;
        int i = 0;
        for (double dx = 0; dx < sx; dx += TILE) {
            for (double dz = 0; dz < sz; dz += TILE) {
                if (i >= lavaTiles.size()) return;
                BlockDisplay d = lavaTiles.get(i++);
                if (d != null && d.isValid()) d.teleport(new Location(d.getWorld(), x0 + dx, lavaY - THICKNESS, z0 + dz));
            }
        }
    }

    /** A few bubbles and flames on the surface near the players. */
    private void lavaParticles() {
        World w = arena.getWorld();
        if (w == null) return;
        Random r = new Random();
        for (PState ps : players.values()) {
            if (!ps.alive || ps.finished) continue;
            Player p = Bukkit.getPlayer(ps.id);
            if (p == null || Math.abs(p.getLocation().getY() - lavaY) > 12) continue;
            for (int i = 0; i < 3; i++) {
                double x = p.getLocation().getX() + (r.nextDouble() - 0.5) * 12;
                double z = p.getLocation().getZ() + (r.nextDouble() - 0.5) * 12;
                if (!arena.getLavaArea().containsXZ(x, z, 0)) continue;
                w.spawnParticle(r.nextBoolean() ? Particle.LAVA : Particle.FLAME, x, lavaY + 0.2, z, 1, 0.1, 0.1, 0.1, 0.02);
            }
        }
    }

    private void removeLavaAndOrbs() {
        for (BlockDisplay d : lavaTiles) if (d != null && d.isValid()) d.remove();
        lavaTiles.clear();
        for (ItemDisplay d : orbDisplays) if (d != null && d.isValid()) d.remove();
        orbDisplays.clear();
    }

    // ── End ───────────────────────────────────────────────────────────────────

    @Override
    protected void onGameEnd() {
        climbing = false;
        if (loop != null) { loop.cancel(); loop = null; }
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        removeLavaAndOrbs();

        // finishers by arrival, survivors by height, then the eliminated (the latest one out first)
        List<PState> ranked = new ArrayList<>();
        for (UUID id : finishOrder) { PState ps = players.get(id); if (ps != null) ranked.add(ps); }
        List<PState> survivors = new ArrayList<>();
        for (PState ps : players.values()) if (ps.alive && !ps.finished) survivors.add(ps);
        survivors.sort((a, b) -> Double.compare(b.maxY, a.maxY));
        ranked.addAll(survivors);
        List<UUID> out = new ArrayList<>(eliminatedOrder);
        Collections.reverse(out);
        for (UUID id : out) {
            PState ps = players.get(id);
            if (ps != null && !ranked.contains(ps)) ranked.add(ps);
        }

        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            for (String line : ChatLayout.header('c', api.tr(vid, "inferno.final"), "")) v.sendMessage(line);
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                PState ps = ranked.get(i);
                String medal = i == 0 ? "§6§l#1" : i == 1 ? "§7§l#2" : i == 2 ? "§c§l#3" : "§8#" + (i + 1);
                String how = ps.finished ? "§a" + fmt(ps.finishMs) : "§7" + api.tr(vid, "inferno.reached", percentOf(ps));
                v.sendMessage("  " + medal + " §f" + ps.name + " §8— " + how
                        + (ps.orbs.isEmpty() ? "" : " §d✦" + ps.orbs.size()));
            }
            v.sendMessage(ChatLayout.rule('c'));
        }

        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            PState ps = ranked.get(i);
            order.add(ps.id);
            api.points().awardPlayerPlacement(ps.id, i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.id, ps.name, registration.getId(), i == 0);
        }

        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).name;
        UUID mvp = ranked.isEmpty() ? null : ranked.get(0).id;
        String mvpName = ranked.isEmpty() ? null : ranked.get(0).name;

        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            GamePlayerUtil.resetPlayer(p);
            GamePlayerUtil.teleportToLobby(p, lobby);
        }
        players.clear();
        fireResult(winnerDesc, mvp, mvpName, order);
    }

    // ── Scoreboard / boss bar ─────────────────────────────────────────────────

    @Override protected String getScoreboardTitle() { return "§c§lKMC RISING INFERNO"; }

    @Override
    protected List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning() || players.isEmpty()) return defaultScoreboardLines(viewer);
        UUID id = viewer.getUniqueId();
        PState me = players.get(id);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "inferno.sb.alive", aliveCount(), players.size()));
        if (climbing && model != null) {
            double gap = viewer.getLocation().getY() - lavaY;
            l.add(api.tr(id, "inferno.sb.lava", String.format(Locale.ROOT, "%+.1f", gap)));
            l.add(api.tr(id, "inferno.sb.rises", model.speedAt((System.currentTimeMillis() - climbStartMs) / 1000.0) > 0
                    ? String.format(Locale.ROOT, "%.2f", model.speedAt((System.currentTimeMillis() - climbStartMs) / 1000.0))
                    : "—"));
        }
        if (me != null) {
            l.add("");
            if (me.finished) l.add(api.tr(id, "inferno.sb.finished", finishOrder.indexOf(id) + 1));
            else if (!me.alive) l.add(api.tr(id, "inferno.sb.out"));
            else l.add(api.tr(id, "inferno.sb.height", percentOf(me)));
            l.add(api.tr(id, "inferno.sb.orbs", me.orbs.size(), arena.getOrbs().size()));
        }
        return l;
    }

    private void updateBossBar(double elapsed) {
        if (bossBar == null || model == null) return;
        double left = Math.max(0, model.secondsUntil(model.endY()) - elapsed);
        bossBar.setTitle("§c§l🔥 Rising Inferno §8| §7" + aliveCount() + " §7over §8| §e" + mmss((long) left));
        double span = Math.max(1, model.endY() - model.startY());
        bossBar.setProgress(Math.max(0, Math.min(1, 1.0 - (lavaY - model.startY()) / span)));
    }

    // ── Reconnect / validation ────────────────────────────────────────────────

    @Override protected PlayerGameState capturePlayerState(Player player) { return null; }

    @Override protected void restorePlayerState(Player player, PlayerGameState snapshot) { /* unused */ }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Rising Inferno"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                for (String problem : arena.problems()) r.addError("Rising Inferno: " + problem);
                if (arena.isReady() && arena.getOrbs().isEmpty())
                    r.addWarning("Rising Inferno: geen bonus-orbs gezet (/inferno orb add) — de risicovolle route levert niets extra op.");
                return r;
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isParticipant(UUID id) { return players.containsKey(id); }

    public boolean isClimbing() { return climbing; }

    private int aliveCount() {
        int n = 0;
        for (PState ps : players.values()) if (ps.alive && !ps.finished) n++;
        return n;
    }

    /** How far up the tower (0-100%) this player's best height is. */
    private int percentOf(PState ps) {
        Integer base = arena.getLavaBaseY();
        if (base == null) return 0;
        double top = Math.max(base + 1, arena.finishFloorY());
        double y = ps.finished ? top : Math.max(base, ps.maxY);
        return (int) Math.max(0, Math.min(100, Math.round((y - base) / (top - base) * 100)));
    }

    private static String mmss(long seconds) { return String.format("%02d:%02d", seconds / 60, seconds % 60); }

    public static String fmt(long ms) {
        long cs = ms / 10;
        return String.format("%d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }
}
