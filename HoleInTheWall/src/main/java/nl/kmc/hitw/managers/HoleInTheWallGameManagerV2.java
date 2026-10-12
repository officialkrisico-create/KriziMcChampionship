package nl.kmc.hitw.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.event.GameObjectiveEvent;
import nl.kmc.game.api.ArenaValidator;
import nl.kmc.game.api.BaseGameManager;
import nl.kmc.game.api.GamePlayerUtil;
import nl.kmc.game.api.GameSfx;
import nl.kmc.game.api.PlayerGameState;
import nl.kmc.game.api.StandardStartFlow;
import nl.kmc.game.api.ValidationResult;
import nl.kmc.hitw.HoleInTheWallPlugin;
import nl.kmc.hitw.config.EventDef;
import nl.kmc.hitw.config.HitwConfig;
import nl.kmc.hitw.config.Modifiers;
import nl.kmc.hitw.models.Arena;
import nl.kmc.hitw.models.PlayerState;
import nl.kmc.hitw.wall.Wall;
import nl.kmc.hitw.wall.WallCollision;
import nl.kmc.hitw.wall.WallSpec;
import nl.kmc.hitw.wall.WallTypeDef;
import nl.kmc.kmccore.presentation.ChatLayout;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Hole in the Wall — straight walls with gaps sweep across a round platform; hit one (or fall off) and
 * you're out. Several rounds; per-round placement points; the total decides the final standing.
 *
 * <p>Round flow: countdown → {@link #tick()} every server tick (events, wall spawning, wall movement,
 * collision, fall check, round-end check) → results → next round or {@link #end()}.
 */
public final class HoleInTheWallGameManagerV2 extends BaseGameManager {

    private static final double DT = 0.05;                 // one server tick, in seconds

    /** How a player went out: pushed/walked off the platform, or left the game. Walls only push — they never eliminate. */
    private enum Cause { FELL, QUIT }

    private final HoleInTheWallPlugin plugin;
    private final HitwConfig cfg;
    private final Arena arena;
    private final WallManager walls;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final List<BukkitTask> pending = new ArrayList<>();
    private final Set<Integer> creditedWalls = new HashSet<>();

    private StandardStartFlow startFlow;
    private BossBar bossBar;
    private BukkitTask loop;

    private Random rnd = new Random();
    private PatternPlanner planner;
    private EventScheduler events;

    private int round, roundGen;
    private boolean roundActive;
    private long roundTicks, nextSpawnTick;
    private int startedAlive;
    private double currentSpeed;
    private String nextWallLabel = "-";
    private String eventLabel = "";

    public HoleInTheWallGameManagerV2(HoleInTheWallPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
        this.cfg = plugin.getHitwConfig();
        this.arena = plugin.getArenaManager().getArena();
        this.walls = new WallManager(plugin, cfg, arena);
    }

    public WallManager getWallManager() { return walls; }

    // ── Prepare / start ──────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        cfg.load(plugin.getConfig());
        players.clear();
        round = 0; roundGen++; roundActive = false;
        walls.removeStrays();
        walls.clear();
        arena.buildPlatform();

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Location> spots = arena.spawnPositions(Math.max(1, online.size()), new Random());
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getName()));
            GamePlayerUtil.resetPlayer(p);
            p.teleport(spots.get(i));
        }

        bossBar = Bukkit.createBossBar("§6§lHole in the Wall", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        online.forEach(bossBar::addPlayer);

        List<Player> parts = new ArrayList<>(online);
        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§6§lHOLE IN THE WALL"; }
                    @Override public String introSubtitle() { return "@hitw.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@hitw.tut.1", "@hitw.tut.2", "@hitw.tut.3", "@hitw.tut.4");
                    }
                    @Override public Location flyoverCenter() { return arena.centerLocation(); }
                    @Override public void onFinished() { beginRound(1); }
                });
        startFlow.prepareAndFreeze();
    }

    @Override protected void onCountdownStart() { /* presentation runs from onGameStart */ }

    @Override protected void onGameStart() { startFlow.start(); }

    // ── Rounds ───────────────────────────────────────────────────────────────

    private void beginRound(int n) {
        if (!getState().isRunning()) return;
        round = n;
        final int gen = ++roundGen;
        roundActive = false;
        roundTicks = 0;
        creditedWalls.clear();
        cancelPending();
        stopLoop();
        walls.clear();

        cfg.load(plugin.getConfig());
        rnd = new Random();
        planner = new PatternPlanner(cfg.patterns, cfg.directionsDeg, rnd);
        events  = new EventScheduler(cfg.events, cfg.eventsEnabled, cfg.eventsStartAfter,
                cfg.eventsMinGap, cfg.eventsMaxGap, rnd);
        nextSpawnTick = Math.round(cfg.firstWallDelaySeconds * 20);
        currentSpeed = cfg.speedCurve.speedAt(0);
        eventLabel = "";
        refreshNextWallLabel(0, Modifiers.NONE);

        List<PlayerState> list = new ArrayList<>(players.values());
        List<Location> spots = arena.spawnPositions(Math.max(1, list.size()), rnd);
        int idx = 0;
        for (PlayerState ps : list) {
            ps.startRound();
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) { ps.eliminate(0, list.size()); continue; }     // offline: out before it starts
            GamePlayerUtil.resetPlayer(p);
            p.teleport(spots.get(idx++ % spots.size()));
            GamePlayerUtil.freezePlayer(p, 20 * (cfg.roundCountdownSeconds + 8));
            p.setGameMode(GameMode.ADVENTURE);
        }
        startedAlive = aliveCount();
        if (startedAlive == 0) { end(); return; }
        updateBossBar();

        int cd = cfg.roundCountdownSeconds;
        for (int i = 0; i <= cd; i++) {
            int num = cd - i;
            later(gen, i * 20L, () -> showCountdown(num));
        }
        later(gen, cd * 20L + 1, () -> startRoundLoop(gen));
    }

    private void showCountdown(int num) {
        for (PlayerState ps : players.values()) {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            if (num > 0) {
                p.sendTitle("§e§l" + num, api.tr(p.getUniqueId(), "hitw.round-sub", round, cfg.rounds), 0, 22, 4);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f);
            } else {
                p.sendTitle(api.tr(p.getUniqueId(), "hitw.go.title"), api.tr(p.getUniqueId(), "hitw.go.sub"), 0, 25, 8);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
                GamePlayerUtil.unfreezePlayer(p);
            }
        }
    }

    private void startRoundLoop(int gen) {
        if (gen != roundGen || !getState().isRunning()) return;
        roundActive = true;
        loop = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** One server tick of a live round. */
    private void tick() {
        if (!roundActive || !getState().isRunning()) return;
        roundTicks++;
        double t = roundTicks * DT;

        EventDef started = events.update(t);
        if (started != null) announceEvent(started);
        Modifiers mods = events.modifiers();
        eventLabel = events.hasActive() ? events.activeEvents().get(0).title() : "";

        currentSpeed = (cfg.speedCurve.speedAt(t) + mods.speedBonus()) * mods.speedMult();

        trySpawnWall(t, mods);
        walls.advance(DT, currentSpeed);
        creditClearedWalls();
        resolvePlayers();
        refreshNextWallLabel(t, mods);
        if (roundTicks % 10 == 0) updateBossBar();
        checkRoundEnd();
    }

    private void checkRoundEnd() {
        int alive = aliveCount();
        boolean timeUp = roundTicks >= cfg.roundMaxSeconds * 20L;
        boolean lastStanding = startedAlive >= 2 ? alive <= 1 : alive == 0;
        if (timeUp || lastStanding) endRound();
    }

    private void endRound() {
        if (!roundActive) return;
        roundActive = false;
        stopLoop();
        long ms = roundTicks * 50L;

        List<PlayerState> survivors = new ArrayList<>();
        for (PlayerState ps : players.values()) if (ps.isAlive()) survivors.add(ps);
        for (PlayerState ps : survivors) ps.survive(ms, 1);          // everyone still standing shares first place

        boolean uniqueWinner = survivors.size() == 1;
        for (PlayerState ps : players.values()) {
            int placement = ps.getPlacement();
            ps.finishRound(cfg.pointsForPlacement(placement), placement == 1);
        }
        if (uniqueWinner && startedAlive >= 3) {
            Player w = Bukkit.getPlayer(survivors.get(0).getUuid());
            if (w != null) fireObjective(w, GameObjectiveEvent.Type.LAST_STANDING);
        }

        walls.clear();
        showResults(survivors);

        final int gen = roundGen;
        later(gen, cfg.resultsSeconds * 20L, () -> {
            if (round >= cfg.rounds) end();
            else beginRound(round + 1);
        });
    }

    private void showResults(List<PlayerState> survivors) {
        List<PlayerState> sorted = new ArrayList<>(players.values());
        sorted.sort((a, b) -> a.getPlacement() != b.getPlacement()
                ? Integer.compare(a.getPlacement(), b.getPlacement())
                : Long.compare(b.getSurvivalMs(), a.getSurvivalMs()));

        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            String winner = survivors.size() == 1 ? survivors.get(0).getName()
                    : survivors.isEmpty() ? api.tr(vid, "hitw.nobody") : api.tr(vid, "hitw.n-players", survivors.size());
            for (String line : ChatLayout.header('6', api.tr(vid, "hitw.round-done", round, cfg.rounds),
                    api.tr(vid, "hitw.winner", winner))) v.sendMessage(line);
            int shown = 0;
            for (PlayerState ps : sorted) {
                if (shown++ >= 8) break;
                String medal = ps.getPlacement() == 1 ? "§6§l#1" : ps.getPlacement() == 2 ? "§7§l#2"
                        : ps.getPlacement() == 3 ? "§c§l#3" : "§8#" + ps.getPlacement();
                v.sendMessage("  " + medal + " §f" + ps.getName() + " §8— §a" + formatTime(ps.getSurvivalMs())
                        + " §8(§e+" + cfg.pointsForPlacement(ps.getPlacement()) + "§8)");
            }
            if (sorted.size() > 8) v.sendMessage(api.tr(vid, "hitw.others", sorted.size() - 8));
            v.sendMessage(ChatLayout.rule('6'));

            PlayerState ps = players.get(vid);
            if (ps == null) continue;
            v.sendTitle(api.tr(vid, "hitw.round-done-title", round), api.tr(vid, "hitw.winner", winner), 5, 60, 15);
            v.playSound(v.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            v.sendMessage(api.tr(vid, "hitw.you", ps.getPlacement(), formatTime(ps.getSurvivalMs()), ps.getTotalPoints()));
        }
    }

    // ── Walls: spawning ──────────────────────────────────────────────────────

    private void trySpawnWall(double t, Modifiers mods) {
        if (roundTicks < nextSpawnTick) return;
        WallSpec spec = planner.peek(t, mods.patternWeights());
        if (spec == null) return;
        WallTypeDef def = cfg.wallTypes.get(spec.typeId());
        if (def == null) { planner.poll(t, mods.patternWeights()); return; }   // unknown type in a pattern: skip it

        double radius = arena.getRadius();
        double spawnS = walls.spawnDistance();
        double factor = def.speedMult() * spec.speedMult();
        double newV = Math.max(0.01, currentSpeed * factor);

        for (Wall w : walls.walls()) {
            // A wall from another side must be completely off the platform first.
            if (spec.differentSide(w.spec)) {
                if (w.s + w.shape.thickness() / 2 > -radius) return;
                continue;
            }
            // Same side: keep the walls far enough apart in TIME at both ends of the platform, so a faster
            // wall can never catch up with (and overlap) the one in front of it.
            double oldV = Math.max(0.01, currentSpeed * w.speedFactor);
            for (double sp : new double[]{-radius, radius}) {
                double tOld = (w.s - sp) / oldV;
                if (tOld < -0.5) continue;                       // that wall is already past this position
                double tNew = (spawnS - sp) / newV;
                if (tNew - tOld < cfg.minArrivalSeparationSeconds) return;
            }
        }

        planner.poll(t, mods.patternWeights());
        double anchor = anchorFor(spec);
        double secondsToPlatform = Math.max(0.3, (spawnS - radius) / newV);
        double reach = Math.max(cfg.minReach, cfg.sprintSpeed * cfg.reachFactor * secondsToPlatform);
        int alive = aliveCount();
        double gapScale = Math.min(cfg.gapMaxScale,
                1 + cfg.gapExtraPerPlayer * Math.max(0, alive - cfg.gapPlayersForBase)) * mods.gapScale();

        Wall wall = walls.spawn(spec, walls.context(anchor, reach, gapScale, rnd));
        double spacing = cfg.spacingBlocks * mods.spacingMult();
        nextSpawnTick = roundTicks + Math.max(10, Math.round(spacing / Math.max(1, currentSpeed * factor) * 20));
        announceWall(wall);
    }

    /**
     * Where the next gap should sit, sideways, in the new wall's frame: where the previous wall (same side)
     * left its gap, else the middle of where the living players currently stand — so every wall is reachable.
     */
    private double anchorFor(WallSpec spec) {
        for (int i = walls.walls().size() - 1; i >= 0; i--) {
            Wall w = walls.walls().get(i);
            if (!w.spec.differentSide(spec) && w.shape.hasGap()) return w.shape.gapCenter();
        }
        double cos = Math.cos(spec.angleRad()), sin = Math.sin(spec.angleRad());
        List<Double> us = new ArrayList<>();
        for (PlayerState ps : players.values()) {
            if (!ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            double dx = p.getLocation().getX() - arena.centerX(), dz = p.getLocation().getZ() - arena.centerZ();
            us.add(-dx * sin + dz * cos);
        }
        if (us.isEmpty()) return 0;
        Collections.sort(us);
        return us.get(us.size() / 2);
    }

    private void announceWall(Wall w) {
        for (PlayerState ps : players.values()) {
            if (!ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            String text = api.tr(ps.getUuid(), "hitw.wall-warn", w.shape.displayName(),
                    api.tr(ps.getUuid(), compass(w.spec.angleRad())));
            p.sendActionBar(net.kyori.adventure.text.Component.text(text));
        }
    }

    private void creditClearedWalls() {
        for (Wall w : walls.walls()) {
            if (!w.clearedPlatform || !creditedWalls.add(w.id)) continue;
            for (PlayerState ps : players.values()) {
                if (!ps.isAlive() || ps.wasHitBy(w.id)) continue;   // a wall that hit you doesn't count as survived
                ps.addWallCleared();
                if (!ps.isMilestoneFired() && ps.getWallsCleared() >= cfg.wallsMilestone) {
                    ps.markMilestoneFired();
                    Player p = Bukkit.getPlayer(ps.getUuid());
                    if (p != null) fireObjective(p, GameObjectiveEvent.Type.WALLS_SURVIVED_MILESTONE);
                }
            }
        }
    }

    private void refreshNextWallLabel(double t, Modifiers mods) {
        WallSpec next = planner == null ? null : planner.peek(t, mods.patternWeights());
        if (next == null) { nextWallLabel = "-"; return; }
        WallTypeDef def = cfg.wallTypes.get(next.typeId());
        nextWallLabel = "§c§l" + (def != null ? def.displayName() : next.typeId());
    }

    // ── Players: collision, falling, elimination ─────────────────────────────

    private void resolvePlayers() {
        double radius = Math.max(0.1, cfg.playerRadius - cfg.tolerance);
        double cx = arena.centerX(), cz = arena.centerZ(), floor = arena.floorY();
        List<PlayerState> victims = new ArrayList<>();
        Map<UUID, Cause> causes = new LinkedHashMap<>();

        for (PlayerState ps : players.values()) {
            if (!ps.isAlive()) continue;
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) { victims.add(ps); causes.put(ps.getUuid(), Cause.QUIT); continue; }

            Location l = p.getLocation();
            double x = l.getX(), y = l.getY(), z = l.getZ();
            if (l.getWorld() != arena.getWorld() || y < floor - cfg.fallBelow) {
                victims.add(ps); causes.put(ps.getUuid(), Cause.FELL); continue;
            }

            double height = p.isSneaking() ? cfg.sneakHeight : cfg.standHeight;
            double px = ps.hasPrev() ? ps.getPrevX() : x, py = ps.hasPrev() ? ps.getPrevY() : y, pz = ps.hasPrev() ? ps.getPrevZ() : z;
            for (Wall w : walls.walls()) {
                double minS = Math.min(w.prevS, w.s) - w.shape.thickness(), maxS = Math.max(w.prevS, w.s) + w.shape.thickness();
                if (minS > arena.getRadius() + 2 || maxS < -arena.getRadius() - 2) continue;   // not over the platform
                if (WallCollision.sweepHits(w.shape, cx, cz, floor, w.prevS, w.prevAlpha, w.s, w.alpha,
                        px, py, pz, x, y, z, height, radius)) {
                    pushByWall(p, ps, w);       // a wall doesn't eliminate — it shoves you toward the edge
                    break;
                }
            }
            ps.rememberPosition(x, y, z);
        }
        if (!victims.isEmpty()) eliminate(victims, causes);
    }

    /**
     * Being hit by a wall carries you along with it: every tick you overlap it your velocity is set to move
     * with (and a little faster than) the wall, away from the side it came from. A wall spans the whole
     * platform, so there is nowhere to dodge sideways — you are shoved off the far edge and fall.
     */
    private void pushByWall(Player p, PlayerState ps, Wall w) {
        double blocksPerTick = currentSpeed * w.speedFactor * DT;
        double magnitude = Math.min(1.3, blocksPerTick * 1.5 + 0.4);
        Vector push = new Vector(-Math.cos(w.alpha), 0, -Math.sin(w.alpha)).multiply(magnitude);
        push.setY(Math.max(p.getVelocity().getY(), 0.12));
        p.setVelocity(push);

        if (ps.markHitBy(w.id)) {                                 // feedback once per wall, not every tick
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f);
            p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 1, 0), 1);
            p.sendActionBar(net.kyori.adventure.text.Component.text(api.tr(p.getUniqueId(), "hitw.hit")));
        }
    }

    /**
     * Eliminates everyone who went out this tick (fell off, or left). Players who go out on the same tick
     * share a placement (one above the number still alive), so nobody is ranked above someone who lasted
     * exactly as long.
     */
    private void eliminate(List<PlayerState> victims, Map<UUID, Cause> causes) {
        int aliveAfter = aliveCount() - victims.size();
        int placement = Math.max(1, aliveAfter + 1);
        long ms = roundTicks * 50L;

        for (PlayerState ps : victims) {
            ps.eliminate(ms, placement);
            Cause cause = causes.getOrDefault(ps.getUuid(), Cause.FELL);
            Player p = Bukkit.getPlayer(ps.getUuid());

            for (Player v : Bukkit.getOnlinePlayers()) {
                v.sendMessage(api.tr(v.getUniqueId(), cause == Cause.FELL ? "hitw.eliminated-fell" : "hitw.eliminated",
                        ps.getName().toUpperCase()));
            }
            if (p == null) continue;

            p.sendTitle(api.tr(p.getUniqueId(), "hitw.out.title"), "§7" + formatTime(ms) + " §8| §e#" + placement, 5, 50, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
            toSpectator(p);
        }
    }

    private void toSpectator(Player p) {
        if (!p.isOnline()) return;
        PlayerState ps = players.get(p.getUniqueId());
        if (ps != null && ps.isAlive()) return;                  // a new round already revived them
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(arena.spectatorSpawn());
    }

    /** Called by the listener when a participant leaves mid-round. */
    public void handleQuit(UUID uuid) {
        PlayerState ps = players.get(uuid);
        if (ps == null || !roundActive || !ps.isAlive()) return;
        eliminate(List.of(ps), Map.of(uuid, Cause.QUIT));
        checkRoundEnd();
    }

    // ── Events ───────────────────────────────────────────────────────────────

    private void announceEvent(EventDef def) {
        for (PlayerState ps : players.values()) {
            Player p = Bukkit.getPlayer(ps.getUuid());
            if (p == null) continue;
            p.sendTitle("§c§l⚡ " + color(def.title()), color(def.subtitle()), 5, 35, 10);
            Sound s = GameSfx.lookupVanillaSound(def.sound());
            p.playSound(p.getLocation(), s != null ? s : Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.3f);
        }
        broadcast("§c§l⚡ " + color(def.title()) + (def.subtitle().isBlank() ? "" : " §8— §7" + color(def.subtitle())));
    }

    // ── End ──────────────────────────────────────────────────────────────────

    @Override
    protected void onGameEnd() {
        roundActive = false;
        roundGen++;
        cancelPending();
        stopLoop();
        walls.clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }

        List<PlayerState> ranked = new ArrayList<>(players.values());
        ranked.sort((a, b) -> {
            if (a.getTotalPoints() != b.getTotalPoints()) return Integer.compare(b.getTotalPoints(), a.getTotalPoints());
            if (a.getTotalSurvivalMs() != b.getTotalSurvivalMs()) return Long.compare(b.getTotalSurvivalMs(), a.getTotalSurvivalMs());
            return Integer.compare(b.getRoundsWon(), a.getRoundsWon());
        });

        for (Player v : Bukkit.getOnlinePlayers()) {
            UUID vid = v.getUniqueId();
            for (String line : ChatLayout.header('6', api.tr(vid, "hitw.final"), "")) v.sendMessage(line);
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                PlayerState ps = ranked.get(i);
                String medal = i == 0 ? "§6§l#1" : i == 1 ? "§7§l#2" : i == 2 ? "§c§l#3" : "§8#" + (i + 1);
                v.sendMessage("  " + medal + " §f" + ps.getName() + " §8— "
                        + api.tr(vid, "hitw.final-line", ps.getTotalPoints(), ps.getRoundsWon(), ps.getBestWalls()));
            }
            v.sendMessage(ChatLayout.rule('6'));
        }

        List<UUID> finishOrder = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            PlayerState ps = ranked.get(i);
            finishOrder.add(ps.getUuid());
            api.points().awardPlayerPlacement(ps.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(ps.getUuid(), ps.getName(), registration.getId(), i == 0);
            if (cfg.rounds >= 2 && ps.getRoundsPlayed() >= cfg.rounds && ps.getRoundsWon() >= cfg.rounds) {
                Player p = Bukkit.getPlayer(ps.getUuid());
                if (p != null) fireObjective(p, GameObjectiveEvent.Type.CLEAN_SWEEP);
            }
        }

        String winnerDesc = ranked.isEmpty() ? "No winner" : ranked.get(0).getName();
        UUID mvp = ranked.isEmpty() ? null : ranked.get(0).getUuid();
        String mvpName = ranked.isEmpty() ? null : ranked.get(0).getName();

        returnToLobby();
        players.clear();
        fireResult(winnerDesc, mvp, mvpName, finishOrder);
    }

    private void returnToLobby() {
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            GamePlayerUtil.resetPlayer(p);
            nl.kmc.game.api.GamePlayerUtil.teleportToLobby(p, lobby);
        }
    }

    // ── Scoreboard / boss bar ────────────────────────────────────────────────

    @Override
    protected String getScoreboardTitle() { return "§6§lKMC HOLE IN THE WALL"; }

    @Override
    protected List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning() || players.isEmpty() || round == 0) return defaultScoreboardLines(viewer);
        UUID id = viewer.getUniqueId();
        PlayerState me = players.get(id);
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "sb.hitw.round", round, cfg.rounds));

        boolean out = me != null && !me.isAlive() && me.getPlacement() > 0;
        if (out) {
            l.add("");
            l.add(api.tr(id, "sb.hitw.eliminated"));
            l.add("");
            l.add(api.tr(id, "sb.hitw.survival"));
            l.add("§f" + formatTime(me.getSurvivalMs()));
            l.add(api.tr(id, "sb.hitw.placement", me.getPlacement()));
            l.add("");
            l.add(api.tr(id, "sb.hitw.alive", aliveCount()));
        } else {
            l.add(api.tr(id, "sb.hitw.alive", aliveCount()));
            l.add(api.tr(id, "sb.hitw.time", mmss(roundTicks / 20)));
            l.add(api.tr(id, "sb.hitw.next", nextWallLabel));
            if (!eventLabel.isEmpty()) l.add(api.tr(id, "sb.hitw.event", color(eventLabel)));
            l.add("");
            l.add(api.tr(id, "sb.hitw.speed", speedBars()));
        }
        if (me != null) l.add(api.tr(id, "sb.common.points", me.getTotalPoints()));
        return l;
    }

    private String speedBars() {
        double max = Math.max(1, cfg.speedCurve.speedAt(1e9));
        int level = (int) Math.max(1, Math.min(5, Math.round(currentSpeed / max * 5)));
        return "§6" + "▮".repeat(level) + "§8" + "▮".repeat(5 - level);
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        bossBar.setTitle("§6§lHole in the Wall §8| §7R §e" + round + "/" + cfg.rounds
                + " §8| §7▶ " + nextWallLabel);
        double progress = roundActive ? 1.0 - (double) roundTicks / (cfg.roundMaxSeconds * 20.0) : 1.0;
        bossBar.setProgress(Math.max(0, Math.min(1, progress)));
    }

    // ── Reconnect (not supported: leaving mid-round eliminates you) ──────────

    @Override protected PlayerGameState capturePlayerState(Player player) { return null; }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) { /* unused */ }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Hole in the Wall"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                if (!plugin.getArenaManager().isReady())
                    r.addError("Hole in the Wall arena niet klaar: " + plugin.getArenaManager().getReadinessReport());
                cfg.load(plugin.getConfig());
                if (cfg.wallTypes.all().isEmpty()) r.addError("Geen wall-types in config.yml.");
                if (cfg.patterns.isEmpty()) r.addWarning("Geen patterns in config.yml — alleen NORMAL-muren.");
                return r;
            }
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    public Map<UUID, PlayerState> getPlayers() { return Collections.unmodifiableMap(players); }
    public boolean isParticipant(UUID id) { return players.containsKey(id); }

    private int aliveCount() {
        int n = 0;
        for (PlayerState ps : players.values()) if (ps.isAlive()) n++;
        return n;
    }

    private void later(int gen, long delayTicks, Runnable r) {
        pending.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gen == roundGen && getState().isRunning()) r.run();
        }, Math.max(0, delayTicks)));
    }

    private void cancelPending() {
        for (BukkitTask t : pending) t.cancel();
        pending.clear();
    }

    private void stopLoop() {
        if (loop != null) { loop.cancel(); loop = null; }
    }

    private void fireObjective(Player p, GameObjectiveEvent.Type type) {
        try { new GameObjectiveEvent(p, registration.getId(), type).callEvent(); }
        catch (Throwable t) { plugin.getLogger().warning("GameObjectiveEvent failed: " + t); }
    }

    private static String color(String s) { return org.bukkit.ChatColor.translateAlternateColorCodes('&', s); }

    private static String compass(double angleRad) {
        double deg = ((Math.toDegrees(angleRad) % 360) + 360) % 360;
        return "hitw.compass." + ((int) Math.round(deg / 45.0) % 8);
    }

    private static String mmss(long seconds) { return String.format("%02d:%02d", seconds / 60, seconds % 60); }

    /** {@code mm:ss.cc} — survival times are shown to the hundredth. */
    public static String formatTime(long ms) {
        long centis = ms / 10;
        return String.format("%02d:%02d.%02d", centis / 6000, (centis / 100) % 60, centis % 100);
    }
}
