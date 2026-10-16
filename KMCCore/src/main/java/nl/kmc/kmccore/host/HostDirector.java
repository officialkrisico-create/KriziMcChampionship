package nl.kmc.kmccore.host;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.host.HostDialogues.Stage;
import nl.kmc.kmccore.host.HostQueue.Speech;
import nl.kmc.kmccore.host.HostQueue.SpeechLine;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Stages a speech: while the lines are shown the host walks, jumps and gestures, the camera moves from shot
 * to shot (push-ins, a fly-over, an orbit) and the tension rises with a heartbeat and particles until the
 * climax. {@link SceneMath} does the planning; this class turns it into a camera route and scheduled actions.
 *
 * <p>It runs on the same tick clock as the speech playback, so a shot changes exactly when its line appears.
 * It never outlives the speech: {@link #stop()} ends the camera and puts the host back on its spot.
 */
final class HostDirector {

    static final String ROUTE_KEY = "host-scene";

    private final KMCCore plugin;
    private final Function<String, Sound> sounds;

    private BukkitTask task;
    private boolean cameraRunning;
    private boolean active;

    private final List<Event> events = new ArrayList<>();
    private int next;
    private long tick;
    private Vector home;
    private Vector dir;
    private World world;
    private long[] display;
    private double[] tension;
    private Predicate<Player> viewers;
    private long nextBeat;
    private long secondBeat;
    private double walkSpeed;
    private boolean particles;
    private boolean heartbeat;
    private boolean thunder;

    private record Event(long t, Runnable run) { }

    HostDirector(KMCCore plugin, Function<String, Sound> sounds) {
        this.plugin = plugin;
        this.sounds = sounds;
    }

    private boolean flag(String path, boolean def) { return plugin.getConfig().getBoolean("host.scene." + path, def); }

    boolean isActive() { return active; }

    /**
     * Starts the staging of {@code s}. Call it right before the first line's wait begins.
     *
     * @param camera  whether the camera work may run (never during a game or over another cinematic)
     * @param viewers who sees the speech (null = everybody)
     * @return false when there was nothing to stage
     */
    boolean start(Speech s, boolean camera, Predicate<Player> viewers) {
        stop();
        var presenter = plugin.getPresenter();
        if (presenter == null || !presenter.isPlaced() || s.lines.isEmpty()) return false;
        if (!flag("enabled", true)) return false;
        Location spot = presenter.getSpot();
        if (spot == null || spot.getWorld() == null) return false;

        this.world = spot.getWorld();
        this.home = spot.toVector();
        Vector d = spot.getDirection().setY(0);
        this.dir = d.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : d.normalize();
        this.viewers = viewers;
        this.walkSpeed = plugin.getConfig().getDouble("host.scene.walk-speed", 0.21);
        this.particles = flag("particles", true);
        this.heartbeat = flag("heartbeat", true);
        this.thunder = flag("thunder", true);

        List<SpeechLine> lines = s.lines;
        this.display = SceneMath.displayTicks(lines);
        this.tension = SceneMath.tension(lines);
        List<SceneMath.Move> moves = SceneMath.planWalks(lines, display, home, dir, walkSpeed, this::resolve);
        long total = SceneMath.totalTicks(lines);

        events.clear();
        next = 0;
        tick = 0;
        nextBeat = 0;
        secondBeat = -1;
        active = true;

        // The host's actions at the moment each line appears.
        for (int i = 0; i < lines.size(); i++) {
            final int idx = i;
            Stage st = lines.get(i).stage();
            if (st == null || st.isEmpty()) continue;
            long at = display[i];
            if (!st.act().isEmpty()) events.add(new Event(at, () -> act(st.act(), at)));
            if (!st.fx().isEmpty()) events.add(new Event(at, () -> effect(st.fx(), idx)));
            if (!st.title().isEmpty() || !st.subtitle().isEmpty()) {
                long hold = Math.max(20, SceneMath.endTicks(lines)[SceneMath.groupEnd(lines, idx)] - at);
                events.add(new Event(at, () -> title(st, (int) hold)));
            }
        }
        for (SceneMath.Move m : moves) {
            events.add(new Event(m.t0(), () -> presenter.walkTo(loc(m.to()), walkSpeed, null)));
        }
        events.sort(Comparator.comparingLong(Event::t));

        if (camera) startCamera(lines, moves, total);

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    /** Ends the camera, the actions and the movement; the host is back on its spot. */
    void stop() {
        if (task != null) { task.cancel(); task = null; }
        events.clear();
        next = 0;
        var cm = plugin.getCinematicManager();
        if (cameraRunning && cm != null) cm.stopRoute(ROUTE_KEY);
        cameraRunning = false;
        if (active) {
            active = false;
            var presenter = plugin.getPresenter();
            if (presenter != null) presenter.goHome();
        }
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    private void startCamera(List<SpeechLine> lines, List<SceneMath.Move> moves, long total) {
        var cm = plugin.getCinematicManager();
        if (cm == null) return;
        List<SceneMath.Key> keys = SceneMath.camera(lines, t -> SceneMath.hostAt(t, moves, home), dir);
        if (keys.size() < 2) return;
        CameraRoute route = new CameraRoute(ROUTE_KEY, "Host scene");
        long prev = 0;
        for (SceneMath.Key k : keys) {
            Vector focus = SceneMath.hostAt(k.t(), moves, home).add(new Vector(0, SceneMath.BODY, 0));
            Location pos = clampCamera(focus, k.pos());
            pos.setDirection(k.look().clone().subtract(pos.toVector()));
            int dur = (int) Math.max(1, k.t() - prev);
            route.addWaypoint(new CameraWaypoint(pos, dur, InterpolationType.SMOOTH, "", "", ""));
            prev = k.t();
        }
        List<Player> who = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (viewers == null || viewers.test(p)) who.add(p);
        if (who.isEmpty()) return;
        cameraRunning = true;
        cm.playRouteInstance(ROUTE_KEY, route, who, true, () -> cameraRunning = false);
    }

    /** Pulls a camera position in front of any wall or ceiling between it and the host. */
    private Location clampCamera(Vector focus, Vector wanted) {
        Vector to = wanted.clone().subtract(focus);
        double len = to.length();
        if (len > 1.0) {
            try {
                RayTraceResult hit = world.rayTraceBlocks(focus.toLocation(world), to.clone().normalize(), len,
                        org.bukkit.FluidCollisionMode.NEVER, true);
                if (hit != null && hit.getHitPosition() != null) {
                    double hitLen = hit.getHitPosition().distance(focus);
                    double use = Math.max(1.6, hitLen - 0.6);
                    return focus.clone().add(to.normalize().multiply(use)).toLocation(world);
                }
            } catch (Throwable ignored) { }
        }
        return wanted.toLocation(world);
    }

    // ── Walking ──────────────────────────────────────────────────────────────

    private Location loc(Vector v) {
        Location l = v.toLocation(world);
        l.setDirection(dir);
        return l;
    }

    /**
     * Turns a wanted walking target into a real place to stand. The walk goes step by step and stops
     * before a wall, and before the edge of the platform: every half block of the way needs ground under
     * it (at most one block lower or higher), so the host never walks off.
     */
    private Vector resolve(Vector from, Vector wanted) {
        try {
            Vector flat = wanted.clone().subtract(from).setY(0);
            double len = flat.length();
            if (len < 0.05) return from.clone();
            Vector dir1 = flat.clone().normalize();
            // walls first
            RayTraceResult hit = world.rayTraceBlocks(from.toLocation(world).add(0, 1.0, 0), dir1, len,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitPosition() != null) {
                double use = hit.getHitPosition().distance(from.clone().add(new Vector(0, 1.0, 0))) - 0.7;
                len = Math.max(0, Math.min(len, use));
            }
            // then the ground, one half block at a time
            Vector best = from.clone();
            double y = from.getY();
            for (double d = 0.5; d <= len + 1e-6; d += 0.5) {
                Vector p = from.clone().add(dir1.clone().multiply(d));
                Double ground = groundAt(p.getX(), p.getZ(), y);
                if (ground == null) break;               // an edge: stop before it
                // keep half a block of floor under the feet at the very end of the walk
                if (!edgeSafe(p.getX(), p.getZ(), ground)) break;
                y = ground;
                best = new Vector(p.getX(), ground, p.getZ());
            }
            return best;
        } catch (Throwable ignored) { }
        return from.clone();
    }

    /** The standing height at (x, z) within a block of {@code y}, or null when there is no ground there. */
    private Double groundAt(double x, double z, double y) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int y0 = (int) Math.floor(y + 0.01);
        for (int dy = 1; dy >= -1; dy--) {
            int by = y0 + dy;
            if (world.getBlockAt(bx, by - 1, bz).getType().isSolid()
                    && !world.getBlockAt(bx, by, bz).getType().isSolid()
                    && !world.getBlockAt(bx, by + 1, bz).getType().isSolid()) {
                return (double) by;
            }
        }
        return null;
    }

    /** True when the blocks around (x, z) also have ground, so the body does not hang over an edge. */
    private boolean edgeSafe(double x, double z, double y) {
        double r = 0.35;
        for (double dx : new double[]{-r, r}) {
            for (double dz : new double[]{-r, r}) {
                if (groundAt(x + dx, z + dz, y) == null) return false;
            }
        }
        return true;
    }

    // ── Per tick ─────────────────────────────────────────────────────────────

    private void tick() {
        if (!active) return;
        tick++;
        try {
            while (next < events.size() && events.get(next).t() <= tick) events.get(next++).run().run();
            ambient();
        } catch (Throwable t) {
            plugin.getLogger().warning("[KMC] Host scene stopped: " + t);
            stop();
        }
    }

    /** The heartbeat and the particles: stronger and quicker as the tension rises. */
    private void ambient() {
        double ten = SceneMath.tensionAt(tick, display, tension);
        var presenter = plugin.getPresenter();
        Location at = presenter.position();
        if (at == null || at.getWorld() == null) return;

        if (heartbeat && ten >= 0.22) {
            if (tick >= nextBeat) {
                beat(0.35f + (float) (0.9 * ten), 0.55f + (float) (0.55 * ten));
                secondBeat = tick + 5;
                nextBeat = tick + Math.round(46 - 34 * ten);
            }
            if (secondBeat >= 0 && tick >= secondBeat) {
                beat(0.25f + (float) (0.7 * ten), 0.5f + (float) (0.45 * ten));
                secondBeat = -1;
            }
        }
        if (particles && ten >= 0.15 && tick % 3 == 0) {
            int n = 1 + (int) Math.round(ten * 6);
            Location c = at.clone().add(0, 1.0, 0);
            world.spawnParticle(Particle.END_ROD, c, n, 0.5, 0.8, 0.5, 0.01);
            if (ten > 0.6) world.spawnParticle(Particle.SOUL_FIRE_FLAME, c, n / 2 + 1, 0.4, 0.2, 0.4, 0.02);
        }
    }

    private void beat(float volume, float pitch) {
        Sound s = sounds.apply("ENTITY_WARDEN_HEARTBEAT");
        if (s == null) s = sounds.apply("BLOCK_NOTE_BLOCK_BASEDRUM");
        if (s == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewers == null || viewers.test(p)) p.playSound(p.getLocation(), s, volume, pitch);
        }
    }

    // ── Host actions and effects ─────────────────────────────────────────────

    private void act(String what, long at) {
        var presenter = plugin.getPresenter();
        switch (what) {
            case "jump" -> presenter.jump();
            case "hop" -> presenter.hop(3, 12);
            case "swing" -> presenter.swing();
            case "wave" -> {
                for (int i = 0; i < 3; i++) schedule(at + 1 + i * 7L, presenter::swing);
            }
            case "crouch" -> presenter.setBodyPose(Pose.SNEAKING);
            case "stand" -> presenter.setBodyPose(Pose.STANDING);
            case "turn" -> presenter.lookAtNearest(80);
            case "spin" -> {
                Location l = presenter.position();
                float yaw0 = l == null ? 0f : l.getYaw();
                for (int i = 1; i <= 12; i++) {
                    final float yaw = yaw0 + 30f * i;
                    schedule(at + i, () -> presenter.turnTo(yaw, 0f));
                }
            }
            case "nod" -> {
                presenter.turnTo(presenter.position() == null ? 0f : presenter.position().getYaw(), 22f);
                Location l = presenter.position();
                final float yaw = l == null ? 0f : l.getYaw();
                schedule(at + 7, () -> presenter.turnTo(yaw, 0f));
            }
            default -> { }
        }
    }

    /** Adds an action for a later tick of this scene while it runs. */
    private void schedule(long at, Runnable r) {
        if (!active) return;
        events.add(new Event(Math.max(tick + 1, at), r));
        events.subList(next, events.size()).sort(Comparator.comparingLong(Event::t));
    }

    private void effect(String what, int lineIdx) {
        var presenter = plugin.getPresenter();
        Location at = presenter.position();
        if (at == null || at.getWorld() == null) return;
        switch (what) {
            case "thunder" -> {
                if (!thunder) return;
                Vector side = SceneMath.right(dir).multiply(lineIdx % 2 == 0 ? 6 : -6);
                world.strikeLightningEffect(at.clone().add(side).add(dir.clone().multiply(4)));
                world.strikeLightningEffect(at.clone().subtract(side).subtract(dir.clone().multiply(3)));
            }
            case "burst" -> {
                Location c = at.clone().add(0, 1.2, 0);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, c, 70, 0.8, 0.9, 0.8, 0.45);
                world.spawnParticle(Particle.FIREWORK, c, 50, 1.0, 1.0, 1.0, 0.1);
                Sound s = sounds.apply("ENTITY_FIREWORK_ROCKET_BLAST");
                if (s != null) world.playSound(at, s, 1.2f, 0.9f);
            }
            case "rise" -> {
                for (int i = 0; i < 10; i++) {
                    final int h = i;
                    schedule(tick + i * 2L, () -> world.spawnParticle(Particle.END_ROD,
                            at.clone().add(0, 0.2 + h * 0.35, 0), 8, 0.35, 0.05, 0.35, 0.02));
                }
            }
            case "stomp" -> {
                Sound s = sounds.apply("ENTITY_GENERIC_EXPLODE");
                if (s != null) world.playSound(at, s, 1.0f, 0.6f);
                world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.1, 0), 25, 0.7, 0.05, 0.7, 0.05);
            }
            default -> { }
        }
    }

    private void title(Stage st, int holdTicks) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewers != null && !viewers.test(p)) continue;
            p.sendTitle(HostText.color(st.title()), HostText.color(st.subtitle()), 8, Math.max(20, holdTicks - 18), 10);
        }
    }
}
