package nl.kmc.formula.managers;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.PointAward;
import nl.kmc.formula.FormulaMCPlugin;
import nl.kmc.formula.models.Circuit;
import nl.kmc.formula.models.Gate;
import nl.kmc.formula.models.Racer;
import nl.kmc.game.api.*;
import nl.kmc.stats.service.StatisticsService;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Formula MC — a boat race over a hand-built ice circuit.
 *
 * <p>Flow: onPrepare puts everybody in a boat on the grid (held in place), the shared start presentation runs,
 * and at GO the race goes live: checkpoints in fixed order, laps, lap times, finish order. Collisions between
 * boats and players are switched off for the whole race (see {@link #disableCollisions()}).
 */
public final class FormulaGameManagerV2 extends BaseGameManager {

    public static final String BOAT_TAG = "fmc_boat";

    private final FormulaMCPlugin plugin;

    private final Map<UUID, Racer> racers = new LinkedHashMap<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final Map<UUID, Location> grid = new HashMap<>();
    private Circuit circuit;

    private volatile boolean raceLive;
    private long raceStartMs;

    private BukkitTask holdTask, pollTask, stepTask, visTask, timeLimitTask, graceTask;
    private BossBar bossBar;
    private StandardStartFlow startFlow;

    // collision handling
    private final Map<String, Team.OptionStatus> previousCollision = new HashMap<>();
    private Team boatTeam;

    // visibility (pair keys "a|b" currently hidden)
    private final Set<String> hiddenPairs = new HashSet<>();

    private long fastestLapMs = -1;
    private UUID fastestLapOwner;

    public FormulaGameManagerV2(FormulaMCPlugin plugin, GameRegistration reg, StatisticsService stats) {
        super(plugin, reg, stats);
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPrepare() {
        racers.clear();
        finishOrder.clear();
        grid.clear();
        hiddenPairs.clear();
        fastestLapMs = -1;
        fastestLapOwner = null;
        raceLive = false;

        circuit = plugin.getCircuitManager().pick();
        if (circuit == null) {
            log.severe("[FormulaMC] No ready circuit — aborting.");
            end();
            return;
        }

        disableCollisions();

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            Location slot = gridSlot(i);
            var team = api.teams().getTeamByPlayer(p.getUniqueId());
            Racer r = new Racer(p.getUniqueId(), p.getName(), team.map(t -> t.getId()).orElse("none"));
            racers.put(p.getUniqueId(), r);
            grid.put(p.getUniqueId(), slot);

            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setHealth(20);
            p.setFoodLevel(20);
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            p.teleport(slot);
            spawnBoatFor(p, r, slot);
        }

        bossBar = Bukkit.createBossBar(ChatColor.AQUA + "" + ChatColor.BOLD + "Formula MC — " + circuit.getDisplayName(),
                BarColor.BLUE, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        // Hold every boat on its grid slot until GO (the intro/flyover/countdown run in between).
        holdTask = Bukkit.getScheduler().runTaskTimer(plugin, this::holdOnGrid, 1L, 1L);

        List<Player> parts = racers.keySet().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        Location center = circuit.getLine() != null ? circuit.getLine().center(circuit.getWorld()) : null;
        startFlow = new StandardStartFlow(plugin, api, registration.getId(),
                () -> getState().isRunning(), this::broadcast,
                new StandardStartFlow.Callbacks() {
                    @Override public List<Player> participants() { return parts; }
                    @Override public String introTitle() { return "§b§lFORMULA MC"; }
                    @Override public String introSubtitle() { return "@fmc.intro-sub"; }
                    @Override public List<String> defaultTutorialMessages() {
                        return List.of("@fmc.tut.1|" + circuit.getLaps(), "@fmc.tut.2", "@fmc.tut.3", "@fmc.tut.4");
                    }
                    @Override public Location flyoverCenter() { return center; }
                    @Override public void onFinished() { beginRace(); }
                });
        startFlow.prepareAndFreeze();
    }

    @Override protected void onCountdownStart() { /* presentation runs from onGameStart */ }

    @Override protected void onGameStart() { if (startFlow != null) startFlow.start(); }

    private void beginRace() {
        if (!getState().isRunning()) return;
        raceLive = true;
        raceStartMs = System.currentTimeMillis();
        if (holdTask != null) { holdTask.cancel(); holdTask = null; }

        for (Racer r : racers.values()) {
            r.setLapStartMs(raceStartMs);
            r.setLastMovedMs(raceStartMs);
            Player p = Bukkit.getPlayer(r.getUuid());
            if (p == null) continue;
            GamePlayerUtil.unfreezePlayer(p);
            p.sendTitle("§a§lGO!", api.tr(p.getUniqueId(), "fmc.go-sub", circuit.getLaps()), 0, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1.2f);
        }
        if (bossBar != null) bossBar.setColor(BarColor.GREEN);

        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollRacers, 2L, 2L);
        stepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::assistSteps, 1L, 1L);
        if (plugin.getConfig().getBoolean("visibility.enabled", true)) {
            visTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateVisibility, 10L, 10L);
        }
        int max = plugin.getConfig().getInt("game.max-duration-seconds", 900);
        if (max > 0) {
            timeLimitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (getState().isRunning()) { tellKey("fmc.time-up"); end(); }
            }, max * 20L);
        }
    }

    @Override
    protected void onGameEnd() {
        raceLive = false;
        for (BukkitTask t : new BukkitTask[]{holdTask, pollTask, stepTask, visTask, timeLimitTask, graceTask}) {
            if (t != null) t.cancel();
        }
        holdTask = pollTask = stepTask = visTask = timeLimitTask = graceTask = null;
        if (startFlow != null) { startFlow.cancel(); startFlow = null; }
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }

        showEveryone();
        List<Racer> ranked = ranking();

        // Placement points: finished players by time, the rest by progress (see ranking()).
        for (int i = 0; i < ranked.size(); i++) {
            Racer r = ranked.get(i);
            api.points().awardPlayerPlacement(r.getUuid(), i + 1, ranked.size(), registration.getId());
            api.games().recordGameParticipation(r.getUuid(), r.getName(), registration.getId(), i == 0);
            int perLap = plugin.getConfig().getInt("points.per-lap", 0);
            if (perLap > 0 && r.getLapsCompleted() > 0) {
                api.points().givePoints(r.getUuid(), perLap * r.getLapsCompleted(),
                        PointAward.Reason.OBJECTIVE, registration.getId());
            }
        }
        // Team placement: the team of the best-placed racer ranks first, and so on.
        List<String> teamOrder = new ArrayList<>();
        for (Racer r : ranked) {
            if (!"none".equals(r.getTeamId()) && !teamOrder.contains(r.getTeamId())) teamOrder.add(r.getTeamId());
        }
        for (int i = 0; i < teamOrder.size(); i++) {
            api.points().awardTeamPlacement(teamOrder.get(i), i + 1, registration.getId());
        }

        int fastestBonus = plugin.getConfig().getInt("points.fastest-lap-bonus", 25);
        if (fastestLapOwner != null && fastestBonus > 0) {
            api.points().givePoints(fastestLapOwner, fastestBonus, PointAward.Reason.BONUS, registration.getId());
            Racer fr = racers.get(fastestLapOwner);
            tellKey("fmc.fastest", fr != null ? fr.getName() : "?", fmt(fastestLapMs), fastestBonus);
        }

        for (Player v : Bukkit.getOnlinePlayers()) {
            java.util.UUID vid = v.getUniqueId();
            v.sendMessage(api.tr(vid, "fmc.results"));
            for (int i = 0; i < ranked.size(); i++) {
                Racer r = ranked.get(i);
                String t = r.isFinished() ? fmt(r.getFinishMs()) : "DNF (" + r.getLapsCompleted() + "/" + circuit.getLaps() + ")";
                String medal = i == 0 ? "§6🥇" : i == 1 ? "§7🥈" : i == 2 ? "§c🥉" : "§7#" + (i + 1);
                v.sendMessage("  " + medal + " §f" + r.getName() + " §8— §e" + t
                        + (r.getBestLapMs() > 0 ? " " + api.tr(vid, "fmc.best-lap", fmt(r.getBestLapMs())) : ""));
            }
        }

        // Clean up boats and put everybody back.
        Location lobby = plugin.getKmcCore().getArenaManager().getLobby();
        for (Racer r : racers.values()) {
            removeBoat(r);
            Player p = Bukkit.getPlayer(r.getUuid());
            if (p == null) continue;
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
            GamePlayerUtil.teleportToLobby(p, lobby);
        }
        restoreCollisions();

        String winner = ranked.isEmpty() ? "Niemand" : ranked.get(0).getName();
        UUID mvp = ranked.isEmpty() ? null : ranked.get(0).getUuid();
        List<UUID> order = new ArrayList<>();
        ranked.forEach(r -> order.add(r.getUuid()));
        racers.clear();
        fireResult(winner, mvp, winner, order);
    }

    // ── Boats ─────────────────────────────────────────────────────────────────

    private Location gridSlot(int index) {
        List<Location> sp = circuit.getSpawns();
        Location base = sp.get(index % sp.size()).clone();
        int row = index / sp.size();
        if (row > 0) {
            // More players than grid slots: extra rows start further back.
            double yaw = Math.toRadians(base.getYaw());
            base.add(Math.sin(yaw) * 4.0 * row, 0, -Math.cos(yaw) * 4.0 * row);
        }
        return base;
    }

    @SuppressWarnings("deprecation")
    private void spawnBoatFor(Player p, Racer r, Location at) {
        Boat boat = at.getWorld().spawn(at, boatClass(), b -> {
            b.setInvulnerable(true);
            b.setPersistent(false);
            b.setCustomName("§b" + r.getName());
            b.setCustomNameVisible(false);
            b.getPersistentDataContainer().set(new NamespacedKey(plugin, BOAT_TAG),
                    org.bukkit.persistence.PersistentDataType.STRING, r.getUuid().toString());
            if (plugin.getConfig().getBoolean("boat.work-on-land", true)) b.setWorkOnLand(true);
        });
        r.setBoatId(boat.getUniqueId());
        r.setLastSafe(at.clone());
        boat.addPassenger(p);
        if (boatTeam != null) boatTeam.addEntity(boat);
    }

    private Class<? extends Boat> boatClass() {
        String t = plugin.getConfig().getString("boat.type", "OAK").toUpperCase(Locale.ROOT);
        return switch (t) {
            case "BIRCH" -> org.bukkit.entity.boat.BirchBoat.class;
            case "JUNGLE" -> org.bukkit.entity.boat.JungleBoat.class;
            case "ACACIA" -> org.bukkit.entity.boat.AcaciaBoat.class;
            case "DARK_OAK" -> org.bukkit.entity.boat.DarkOakBoat.class;
            case "CHERRY" -> org.bukkit.entity.boat.CherryBoat.class;
            case "MANGROVE" -> org.bukkit.entity.boat.MangroveBoat.class;
            case "PALE_OAK" -> org.bukkit.entity.boat.PaleOakBoat.class;
            default -> org.bukkit.entity.boat.OakBoat.class;
        };
    }

    private Boat boatOf(Racer r) {
        if (r.getBoatId() == null) return null;
        Entity e = Bukkit.getEntity(r.getBoatId());
        return e instanceof Boat b && b.isValid() ? b : null;
    }

    private void removeBoat(Racer r) {
        Boat b = boatOf(r);
        if (b != null) {
            b.getPassengers().forEach(b::removePassenger);
            b.remove();
        }
        r.setBoatId(null);
    }

    /** Teleports a boat WITH its passenger (passengers are kept by default since Paper 1.21.10). */
    private static void moveBoat(Boat boat, Location to) {
        boat.setVelocity(new Vector(0, 0, 0));
        boat.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    /** Keeps all boats still on their slot before GO. */
    private void holdOnGrid() {
        for (Racer r : racers.values()) {
            Boat b = boatOf(r);
            Location slot = grid.get(r.getUuid());
            Player p = Bukkit.getPlayer(r.getUuid());
            if (slot == null || p == null) continue;
            if (b == null) { respawnBoat(r, p, slot); continue; }
            if (b.getLocation().distanceSquared(slot) > 0.25) moveBoat(b, slot);
            else b.setVelocity(new Vector(0, 0, 0));
            if (!b.getPassengers().contains(p)) b.addPassenger(p);
        }
    }

    /** New boat for a racer whose boat vanished or who got out. */
    private void respawnBoat(Racer r, Player p, Location at) {
        removeBoat(r);
        if (p.getVehicle() != null) p.leaveVehicle();
        p.teleport(at);
        spawnBoatFor(p, r, at);
    }

    // ── Collisions: boats and players never push each other ───────────────────

    private void disableCollisions() {
        try {
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            previousCollision.clear();
            for (Team t : main.getTeams()) {
                previousCollision.put(t.getName(), t.getOption(Team.Option.COLLISION_RULE));
                t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            }
            boatTeam = main.getTeam("kmc_fmc_boats");
            if (boatTeam == null) boatTeam = main.registerNewTeam("kmc_fmc_boats");
            boatTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            boatTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        } catch (Throwable t) {
            log.warning("[FormulaMC] Could not switch off team collisions: " + t);
        }
    }

    private void restoreCollisions() {
        try {
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team t : main.getTeams()) {
                Team.OptionStatus prev = previousCollision.get(t.getName());
                if (prev != null) t.setOption(Team.Option.COLLISION_RULE, prev);
            }
            previousCollision.clear();
            if (boatTeam != null) {
                try { boatTeam.unregister(); } catch (IllegalStateException ignored) { }
                boatTeam = null;
            }
        } catch (Throwable t) {
            log.warning("[FormulaMC] Could not restore team collisions: " + t);
        }
    }

    // ── Polling: checkpoints, laps, stuck racers ──────────────────────────────

    private void pollRacers() {
        if (!raceLive || circuit == null) return;
        long now = System.currentTimeMillis();
        double maxStep = plugin.getConfig().getDouble("game.max-teleport-step", 30.0);
        long stuckMs = plugin.getConfig().getInt("game.stuck-seconds", 8) * 1000L;

        for (Racer r : new ArrayList<>(racers.values())) {
            if (r.isFinished()) continue;
            Player p = Bukkit.getPlayer(r.getUuid());
            if (p == null || !p.isOnline()) continue;
            Boat boat = boatOf(r);
            if (boat == null) { respawnAtSafe(r, p); continue; }
            if (!boat.getPassengers().contains(p)) boat.addPassenger(p);   // knocked out of the boat: back in

            Location cur = boat.getLocation();
            Location prev = r.getLastPoll();
            r.setLastPoll(cur.clone());
            if (prev == null || prev.getWorld() != cur.getWorld()) { r.setLastMovedMs(now); continue; }
            double dist = prev.distance(cur);

            if (dist > 0.12) r.setLastMovedMs(now);
            else if (now - r.getLastMovedMs() > stuckMs) {
                respawnAtSafe(r, p);
                r.setLastMovedMs(now);
                continue;
            }
            if (dist > maxStep) continue;              // teleport: never counts for a checkpoint or lap

            // Sweep the path since the last poll (fast boats cover several blocks per poll).
            int steps = (int) Math.min(40, Math.max(1, Math.ceil(dist)));
            for (int s = 1; s <= steps && !r.isFinished(); s++) {
                double t = (double) s / steps;
                Location sample = prev.clone().add(cur.clone().subtract(prev).toVector().multiply(t));
                if (!advance(r, p, sample, now)) break;
            }
            // Track a safe spot (on the track, not in the air) for resets.
            if (boat.getStatus() != Boat.Status.IN_AIR) r.setLastSafe(cur.clone());
        }
    }

    /** Checks the next required gate; returns false if the racer finished (stop sweeping). */
    private boolean advance(Racer r, Player p, Location at, long now) {
        List<Gate> cps = circuit.getCheckpoints();
        int next = r.getNextCheckpoint();
        if (next < cps.size()) {
            if (cps.get(next).contains(at)) {
                r.setNextCheckpoint(next + 1);
                Gate g = cps.get(next);
                r.setLastSafe(g.center(circuit.getWorld()).add(0, 1, 0));
                p.sendActionBar(net.kyori.adventure.text.Component.text(
                        api.tr(p.getUniqueId(), "fmc.checkpoint", next + 1, cps.size())));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
            }
            return true;
        }
        // all checkpoints of this lap done → the start/finish line completes the lap
        Gate line = circuit.getLine();
        if (line == null || !line.contains(at)) return true;
        long minLap = plugin.getConfig().getInt("game.min-lap-seconds", 4) * 1000L;
        if (now - r.getLapStartMs() < minLap) return true;   // double crossing / shortcut guard

        long lap = r.completeLap(now);
        if (fastestLapMs < 0 || lap < fastestLapMs) { fastestLapMs = lap; fastestLapOwner = r.getUuid(); }

        if (r.getLapsCompleted() >= circuit.getLaps()) {
            finishRacer(r, p, now);
            return false;
        }
        p.sendTitle("", api.tr(p.getUniqueId(), "fmc.lap", r.getLapsCompleted(), circuit.getLaps(), fmt(lap)), 0, 30, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
        int perLap = plugin.getConfig().getInt("points.per-lap", 0);
        if (perLap > 0) p.sendMessage(api.tr(p.getUniqueId(), "fmc.lap-points", perLap));
        return true;
    }

    private void finishRacer(Racer r, Player p, long now) {
        long total = now - raceStartMs;
        int place = finishOrder.size() + 1;
        r.markFinished(total, place);
        finishOrder.add(r.getUuid());

        p.sendTitle("§6§l#" + place, "§e" + fmt(total), 10, 60, 20);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        tellKey("fmc.finished", r.getName(), place, fmt(total));

        removeBoat(r);
        p.setGameMode(GameMode.SPECTATOR);

        if (finishOrder.size() >= racers.size()) { end(); return; }
        if (finishOrder.size() == 1 && racers.size() > 1) {
            int grace = plugin.getConfig().getInt("game.finish-grace-seconds", 60);
            if (grace > 0) {
                tellKey("fmc.grace", grace);
                graceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (getState().isRunning()) { tellKey("fmc.time-up-race"); end(); }
                }, grace * 20L);
            }
        }
    }

    /** Puts a stuck/lost racer back on the track at the last checkpoint (or the grid / reset spot). */
    private void respawnAtSafe(Racer r, Player p) {
        Location to = r.getLastSafe();
        if (to == null) to = circuit.getReset();
        if (to == null) to = grid.get(r.getUuid());
        if (to == null) return;
        Boat b = boatOf(r);
        if (b == null) {
            respawnBoat(r, p, to);
        } else {
            moveBoat(b, to);
            if (!b.getPassengers().contains(p)) b.addPassenger(p);
        }
        p.sendActionBar(net.kyori.adventure.text.Component.text(api.tr(p.getUniqueId(), "fmc.back-on-track")));
    }

    // ── Step assist: ramps of one block never stop a boat ─────────────────────

    private void assistSteps() {
        if (!raceLive || !plugin.getConfig().getBoolean("boat.step-assist", true)) return;
        for (Racer r : racers.values()) {
            if (r.isFinished()) continue;
            Boat b = boatOf(r);
            if (b == null || b.getPassengers().isEmpty()) continue;
            Vector v = b.getVelocity();
            double speed = Math.hypot(v.getX(), v.getZ());
            if (speed < 0.08) continue;
            if (b.getStatus() == Boat.Status.IN_AIR) continue;

            double look = 0.9 + speed * 1.5;
            Location ahead = b.getLocation().add(v.getX() / speed * look, 0, v.getZ() / speed * look);
            var b0 = ahead.getBlock();
            var b1 = ahead.clone().add(0, 1, 0).getBlock();
            var b2 = ahead.clone().add(0, 2, 0).getBlock();
            if (b0.getType().isSolid() && !b1.getType().isSolid() && !b2.getType().isSolid()) {
                // a step of exactly one block: hop it and keep the speed
                b.setVelocity(new Vector(v.getX() * 1.04, 0.42, v.getZ() * 1.04));
            }
        }
    }

    // ── Visibility: other racers only within N blocks ─────────────────────────

    private void updateVisibility() {
        if (!raceLive) return;
        double radius = plugin.getConfig().getDouble("visibility.radius", 10.0);
        double rSq = radius * radius;
        List<Racer> list = new ArrayList<>(racers.values());
        for (int i = 0; i < list.size(); i++) {
            Player a = Bukkit.getPlayer(list.get(i).getUuid());
            if (a == null || !a.isOnline()) continue;
            for (int j = 0; j < list.size(); j++) {
                if (i == j) continue;
                Racer rb = list.get(j);
                Player b = Bukkit.getPlayer(rb.getUuid());
                if (b == null || !b.isOnline()) continue;
                boolean close = a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= rSq;
                String key = a.getUniqueId() + "|" + b.getUniqueId();
                boolean hidden = hiddenPairs.contains(key);
                Boat boatB = boatOf(rb);
                if (close && hidden) {
                    a.showEntity(plugin, b);
                    if (boatB != null) a.showEntity(plugin, boatB);
                    hiddenPairs.remove(key);
                } else if (!close && !hidden) {
                    a.hideEntity(plugin, b);
                    if (boatB != null) a.hideEntity(plugin, boatB);
                    hiddenPairs.add(key);
                }
            }
        }
    }

    private void showEveryone() {
        for (Racer ra : racers.values()) {
            Player a = Bukkit.getPlayer(ra.getUuid());
            if (a == null) continue;
            for (Racer rb : racers.values()) {
                if (ra == rb) continue;
                Player b = Bukkit.getPlayer(rb.getUuid());
                if (b != null) a.showEntity(plugin, b);
                Boat boatB = boatOf(rb);
                if (boatB != null) a.showEntity(plugin, boatB);
            }
        }
        hiddenPairs.clear();
    }

    // ── Ranking / display ─────────────────────────────────────────────────────

    /** Finished racers first (by time), then everyone else by laps → checkpoints → distance to the next gate. */
    private List<Racer> ranking() {
        List<Racer> list = new ArrayList<>(racers.values());
        list.sort((a, b) -> {
            if (a.isFinished() != b.isFinished()) return a.isFinished() ? -1 : 1;
            if (a.isFinished()) return Long.compare(a.getFinishMs(), b.getFinishMs());
            if (a.getLapsCompleted() != b.getLapsCompleted()) return Integer.compare(b.getLapsCompleted(), a.getLapsCompleted());
            if (a.getNextCheckpoint() != b.getNextCheckpoint()) return Integer.compare(b.getNextCheckpoint(), a.getNextCheckpoint());
            return Double.compare(distToNext(a), distToNext(b));
        });
        return list;
    }

    private double distToNext(Racer r) {
        if (circuit == null || r.getLastPoll() == null) return Double.MAX_VALUE;
        Gate g = r.getNextCheckpoint() < circuit.getCheckpoints().size()
                ? circuit.getCheckpoints().get(r.getNextCheckpoint()) : circuit.getLine();
        if (g == null) return Double.MAX_VALUE;
        return g.center(circuit.getWorld()).distance(r.getLastPoll());
    }

    public static String fmt(long ms) {
        if (ms < 0) return "--";
        long cs = ms / 10;
        return String.format("%d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    @Override
    protected List<String> getScoreboardLines(Player viewer) {
        if (!getState().isRunning() || circuit == null) return defaultScoreboardLines(viewer);
        UUID id = viewer.getUniqueId();
        List<String> l = new ArrayList<>();
        l.add(api.tr(id, "fmc.sb.circuit", circuit.getDisplayName()));
        Racer me = racers.get(id);
        if (me != null) {
            List<Racer> rank = ranking();
            l.add(api.tr(id, "fmc.sb.position", rank.indexOf(me) + 1, rank.size()));
            l.add(api.tr(id, "fmc.sb.lap", Math.min(me.getLapsCompleted() + 1, circuit.getLaps()), circuit.getLaps()));
            l.add(api.tr(id, "fmc.sb.last", fmt(me.getLastLapMs())));
            l.add(api.tr(id, "fmc.sb.best", fmt(me.getBestLapMs())));
            long t = raceLive ? (me.isFinished() ? me.getFinishMs() : System.currentTimeMillis() - raceStartMs) : 0;
            l.add(api.tr(id, "fmc.sb.time", fmt(t)));
        } else {
            l.add(api.tr(id, "fmc.sb.time", fmt(raceLive ? System.currentTimeMillis() - raceStartMs : 0)));
        }
        return l;
    }

    // ── State capture (reconnect) ─────────────────────────────────────────────

    @Override
    protected PlayerGameState capturePlayerState(Player player) {
        PlayerGameState s = new PlayerGameState();
        s.inventory = player.getInventory().getContents().clone();
        s.armor = player.getInventory().getArmorContents().clone();
        s.health = player.getHealth();
        s.maxHealth = 20;
        s.location = player.getLocation();
        s.effects = new ArrayList<>(player.getActivePotionEffects());
        return s;
    }

    @Override
    protected void restorePlayerState(Player player, PlayerGameState snapshot) {
        Racer r = racers.get(player.getUniqueId());
        if (r != null && circuit != null) respawnAtSafe(r, player);
        player.setHealth(Math.min(snapshot.health, snapshot.maxHealth));
    }

    @Override
    protected ArenaValidator getArenaValidator() {
        return new ArenaValidator() {
            @Override public String getGameName() { return "Formula MC"; }
            @Override public ValidationResult validate() {
                ValidationResult r = new ValidationResult();
                Circuit c = plugin.getCircuitManager().pick();
                if (c == null) {
                    r.addError("Formula MC: geen circuit klaar. " + problemsText());
                }
                return r;
            }
        };
    }

    private String problemsText() {
        var mgr = plugin.getCircuitManager();
        if (mgr.all().isEmpty()) return "Maak er een met /fmc create <id>.";
        StringBuilder sb = new StringBuilder();
        for (Circuit c : mgr.all().values()) sb.append(c.getId()).append(": ").append(c.problems()).append(" ");
        return sb.toString();
    }

    // ── Listener API ──────────────────────────────────────────────────────────

    public boolean isRaceBoat(Entity e) {
        return e.getPersistentDataContainer().has(new NamespacedKey(plugin, BOAT_TAG),
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    public boolean isRacing(UUID uuid) {
        Racer r = racers.get(uuid);
        return getState().isRunning() && r != null && !r.isFinished();
    }

    public boolean hasRacer(UUID uuid) { return racers.containsKey(uuid); }

    public Map<UUID, Racer> getRacers() { return Collections.unmodifiableMap(racers); }
}
