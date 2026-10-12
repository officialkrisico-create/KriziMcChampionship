package nl.kmc.game.api.arcade;

import java.util.*;

/**
 * Plain-Java building blocks shared by the six arcade games (Rocket Rush, Knockout Kings, Crafting Countdown,
 * Zone Zero, Crown Control, Core Charge). Nothing in here touches Bukkit, so all of it is unit-tested.
 */
public final class ArcadeLogic {

    private ArcadeLogic() { }

    // ── Region ────────────────────────────────────────────────────────────────

    /** An axis-aligned box in one world; corners may be given in any order. Block coordinates are inclusive. */
    public record Region(String world, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        public static Region ofBlocks(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Region(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2) + 1.0, Math.max(y1, y2) + 1.0, Math.max(z1, z2) + 1.0);
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
        }

        public boolean containsXZ(double x, double z) { return x >= minX && x < maxX && z >= minZ && z < maxZ; }

        public double centerX() { return (minX + maxX) / 2; }
        public double centerY() { return (minY + maxY) / 2; }
        public double centerZ() { return (minZ + maxZ) / 2; }
        public double sizeX() { return maxX - minX; }
        public double sizeZ() { return maxZ - minZ; }

        /** The same region shrunk towards its centre in X/Z by {@code fraction} (0 = unchanged, 1 = a point). */
        public Region shrunk(double fraction, double minSize) {
            double f = Math.max(0, Math.min(1, fraction));
            double hx = Math.max(minSize / 2, sizeX() / 2 * (1 - f));
            double hz = Math.max(minSize / 2, sizeZ() / 2 * (1 - f));
            return new Region(world, centerX() - hx, minY, centerZ() - hz, centerX() + hx, maxY, centerZ() + hz);
        }
    }

    // ── One-time awards ───────────────────────────────────────────────────────

    /** Remembers keys so a score event can never be booked twice (e.g. "run:cp:uuid:3"). */
    public static final class OnceGuard {
        private final Set<String> seen = new HashSet<>();

        /** True the first time a key is presented, false afterwards. */
        public synchronized boolean first(String key) { return seen.add(key); }
        public synchronized boolean has(String key) { return seen.contains(key); }
        public synchronized void clear() { seen.clear(); }
        public synchronized int size() { return seen.size(); }
    }

    // ── Score book ────────────────────────────────────────────────────────────

    public record ScoreEvent(long seq, String key, UUID player, String team, String category, int points) {}

    /**
     * Every score event of one game run. Events with a key are booked once; totals per team, player and
     * category feed the HUD and the final ranking.
     */
    public static final class ScoreBook {
        private final List<ScoreEvent> events = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();
        private final Map<String, Integer> teamTotals = new HashMap<>();
        private final Map<UUID, Integer> playerTotals = new HashMap<>();
        private final Map<String, Long> teamLastSeq = new HashMap<>();
        private long seq;

        /** @return false when {@code key} was already booked (the event is ignored). */
        public synchronized boolean add(String key, UUID player, String team, String category, int points) {
            if (key != null && !keys.add(key)) return false;
            ScoreEvent e = new ScoreEvent(++seq, key, player, team, category, points);
            events.add(e);
            if (team != null) {
                teamTotals.merge(team, points, Integer::sum);
                teamLastSeq.put(team, e.seq());
            }
            if (player != null) playerTotals.merge(player, points, Integer::sum);
            return true;
        }

        public synchronized int team(String team) { return teamTotals.getOrDefault(team, 0); }
        public synchronized int player(UUID p) { return playerTotals.getOrDefault(p, 0); }
        public synchronized List<ScoreEvent> events() { return List.copyOf(events); }
        public synchronized void clear() {
            events.clear(); keys.clear(); teamTotals.clear(); playerTotals.clear(); teamLastSeq.clear(); seq = 0;
        }

        public synchronized int categoryTotal(String team, String category) {
            int n = 0;
            for (ScoreEvent e : events) if (team.equals(e.team()) && category.equals(e.category())) n += e.points();
            return n;
        }

        /**
         * Teams by points (high first). Ties go to the team that reached its score first, then to the
         * team id — never to chance.
         */
        public synchronized List<String> rankTeams(Collection<String> teams) {
            List<String> out = new ArrayList<>(teams);
            out.sort((a, b) -> {
                int pa = team(a), pb = team(b);
                if (pa != pb) return Integer.compare(pb, pa);
                long la = teamLastSeq.getOrDefault(a, Long.MAX_VALUE), lb = teamLastSeq.getOrDefault(b, Long.MAX_VALUE);
                if (la != lb) return Long.compare(la, lb);
                return a.compareTo(b);
            });
            return out;
        }

        /** Players by points (high first), ties by UUID so the order is stable. */
        public synchronized List<UUID> rankPlayers(Collection<UUID> players) {
            List<UUID> out = new ArrayList<>(players);
            out.sort((a, b) -> {
                int pa = player(a), pb = player(b);
                if (pa != pb) return Integer.compare(pb, pa);
                return a.compareTo(b);
            });
            return out;
        }
    }

    // ── Capture meter ─────────────────────────────────────────────────────────

    public enum CaptureStatus { NEUTRAL, CAPTURING, CONTROLLED, CONTESTED }

    /**
     * One capturable spot. The meter only runs while exactly one team is present and it is not the owner;
     * with several teams present it pauses (contested); when nobody is there it decays.
     */
    public static final class CaptureMeter {

        /** An ownership change; {@code previous} is null when the spot was neutral. */
        public record Change(String owner, String previous) {}

        private final double captureSeconds;
        private final double decayPerSecond;
        private String owner;
        private String capturer;
        private double progress;
        private CaptureStatus status = CaptureStatus.NEUTRAL;

        public CaptureMeter(double captureSeconds, double decayPerSecond) {
            this.captureSeconds = Math.max(0.1, captureSeconds);
            this.decayPerSecond = Math.max(0, decayPerSecond);
        }

        /**
         * @param present ids of the teams that have a living player on the spot right now
         * @param dt      seconds since the previous call
         * @return the ownership change, or null
         */
        public Change update(Set<String> present, double dt) {
            if (present.size() > 1) {
                status = CaptureStatus.CONTESTED;
                return null;
            }
            if (present.size() == 1) {
                String team = present.iterator().next();
                if (team.equals(owner)) {
                    progress = 0;
                    capturer = null;
                    status = CaptureStatus.CONTROLLED;
                    return null;
                }
                if (!team.equals(capturer)) { capturer = team; progress = 0; }
                progress += dt;
                status = CaptureStatus.CAPTURING;
                if (progress >= captureSeconds) {
                    String previous = owner;
                    owner = team;
                    capturer = null;
                    progress = 0;
                    status = CaptureStatus.CONTROLLED;
                    return new Change(team, previous);
                }
                return null;
            }
            // nobody there
            if (capturer != null) {
                progress = Math.max(0, progress - decayPerSecond * dt);
                if (progress == 0) capturer = null;
            }
            status = capturer != null ? CaptureStatus.CAPTURING : (owner != null ? CaptureStatus.CONTROLLED : CaptureStatus.NEUTRAL);
            return null;
        }

        public void reset() { owner = null; capturer = null; progress = 0; status = CaptureStatus.NEUTRAL; }
        public void setOwner(String team) { owner = team; capturer = null; progress = 0;
            status = team != null ? CaptureStatus.CONTROLLED : CaptureStatus.NEUTRAL; }

        public String owner() { return owner; }
        public String capturer() { return capturer; }
        public CaptureStatus status() { return status; }
        public double fraction() { return Math.min(1, progress / captureSeconds); }
    }

    // ── Team energy ───────────────────────────────────────────────────────────

    /** A bounded resource that is spent atomically: a failed spend never removes anything. */
    public static final class TeamEnergy {
        private final double capacity;
        private double value;

        public TeamEnergy(double capacity) { this.capacity = Math.max(1, capacity); }

        /** @return how much was actually added (the rest overflowed) */
        public synchronized double add(double amount) {
            if (amount <= 0) return 0;
            double accepted = Math.min(amount, capacity - value);
            value += accepted;
            return accepted;
        }

        public synchronized boolean trySpend(double cost) {
            if (cost < 0 || value + 1e-9 < cost) return false;
            value = Math.max(0, value - cost);
            return true;
        }

        public synchronized double value() { return value; }
        public double capacity() { return capacity; }
        public synchronized boolean isFull() { return value >= capacity - 1e-9; }
    }

    // ── Cooldowns ─────────────────────────────────────────────────────────────

    /** Cooldowns in game ticks (so pausing the game pauses them too). */
    public static final class Cooldowns {
        private final Map<String, Long> readyAt = new HashMap<>();

        public synchronized boolean ready(String key, long nowTicks) { return nowTicks >= readyAt.getOrDefault(key, 0L); }
        public synchronized void start(String key, long nowTicks, long durationTicks) { readyAt.put(key, nowTicks + durationTicks); }
        public synchronized long remaining(String key, long nowTicks) { return Math.max(0, readyAt.getOrDefault(key, 0L) - nowTicks); }
        public synchronized void clear() { readyAt.clear(); }
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    /**
     * True when the segment a→b passes within {@code r} of the centre. Used so a fast flyer cannot skip a
     * checkpoint between two position polls.
     */
    public static boolean segmentHitsSphere(double ax, double ay, double az, double bx, double by, double bz,
                                            double cx, double cy, double cz, double r) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 == 0 ? 0 : ((cx - ax) * dx + (cy - ay) * dy + (cz - az) * dz) / len2;
        t = Math.max(0, Math.min(1, t));
        double px = ax + t * dx - cx, py = ay + t * dy - cy, pz = az + t * dz - cz;
        return px * px + py * py + pz * pz <= r * r;
    }

    // ── Round-robin helpers ───────────────────────────────────────────────────

    /**
     * Picks {@code count} distinct items from the pool, avoiding the previous selection when another
     * combination exists.
     */
    public static <T> List<T> pickDistinct(List<T> pool, int count, Collection<T> previous, Random rnd) {
        int n = Math.min(count, pool.size());
        if (n <= 0) return new ArrayList<>();
        List<T> best = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            List<T> copy = new ArrayList<>(pool);
            Collections.shuffle(copy, rnd);
            List<T> pick = new ArrayList<>(copy.subList(0, n));
            best = pick;
            if (previous == null || !(previous.size() == n && previous.containsAll(pick))) return pick;
            if (pool.size() == n) return pick;   // only one combination exists
        }
        return best;
    }
}
