package nl.kmc.laser;

import nl.kmc.laser.LaserBoard.Comp;
import nl.kmc.laser.LaserBoard.Dir;
import nl.kmc.laser.LaserBoard.Type;

import java.util.*;

/**
 * Deterministic beam tracing. The simulation is the single source of truth: which cells carry a beam and
 * which receivers are active. Particles only show its result. A beam state is (cell, direction, colour); a
 * state that is reached a second time is a loop and is not expanded again, and the number of steps is capped,
 * so a puzzle can never hang the server.
 */
public final class LaserSim {

    private LaserSim() { }

    /** A beam passing through a cell. */
    public record Seg(int x, int y, Dir dir, String color) {}

    public static final class Result {
        public final List<Seg> segments = new ArrayList<>();
        /** Receiver id to the colour of the beam that activates it. */
        public final Map<String, String> active = new LinkedHashMap<>();
        public boolean loop;
        public boolean stepLimit;
        public int steps;

        public boolean isActive(String receiverId) { return active.containsKey(receiverId); }
    }

    public static Result trace(LaserBoard board, int maxSteps) {
        Result r = new Result();
        Deque<Seg> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (int[] p : board.find(Type.EMITTER)) {
            Comp e = board.get(p[0], p[1]);
            queue.add(new Seg(p[0], p[1], e.dir, e.color));
        }
        while (!queue.isEmpty()) {
            if (r.steps >= maxSteps) { r.stepLimit = true; break; }
            Seg b = queue.poll();
            if (!seen.add(b.x() + "," + b.y() + "," + b.dir() + "," + b.color())) { r.loop = true; continue; }
            r.steps++;

            int nx = b.x() + b.dir().dx, ny = b.y() + b.dir().dy;
            if (!board.inside(nx, ny)) continue;
            Comp c = board.get(nx, ny);
            Type t = c == null ? Type.EMPTY : c.type;
            switch (t) {
                case WALL, ABSORBER, EMITTER -> { }            // the beam ends in front of it
                case EMPTY -> {
                    r.segments.add(new Seg(nx, ny, b.dir(), b.color()));
                    queue.add(new Seg(nx, ny, b.dir(), b.color()));
                }
                case MIRROR -> {
                    Dir out = b.dir().reflect(c.slash);
                    r.segments.add(new Seg(nx, ny, out, b.color()));
                    queue.add(new Seg(nx, ny, out, b.color()));
                }
                case SPLITTER -> {
                    r.segments.add(new Seg(nx, ny, b.dir(), b.color()));
                    queue.add(new Seg(nx, ny, b.dir().left(), b.color()));
                    queue.add(new Seg(nx, ny, b.dir().right(), b.color()));
                }
                case FILTER -> {
                    r.segments.add(new Seg(nx, ny, b.dir(), c.color));
                    queue.add(new Seg(nx, ny, b.dir(), c.color));
                }
                case RECEIVER -> {
                    r.segments.add(new Seg(nx, ny, b.dir(), b.color()));
                    boolean colourOk = c.needColor == null || c.needColor.equalsIgnoreCase(b.color());
                    boolean dirOk = c.needDir == null || c.needDir == b.dir();
                    if (colourOk && dirOk && c.id != null) r.active.putIfAbsent(c.id, b.color());
                }
            }
        }
        return r;
    }

    /** True when every goal receiver is active. */
    public static boolean solved(LaserBoard board, Result r) {
        Set<String> goals = board.goalReceivers();
        if (goals.isEmpty()) return false;
        for (String id : goals) if (!r.isActive(id)) return false;
        return true;
    }

    public static boolean solved(LaserBoard board, int maxSteps) {
        return solved(board, trace(board, maxSteps));
    }

    /** May this mirror be turned right now: rotatable, and its unlocking receiver (if any) is active. */
    public static boolean canRotate(Comp c, Result current) {
        if (c == null || c.type != Type.MIRROR || !c.rotatable) return false;
        return c.unlockedBy == null || current.isActive(c.unlockedBy);
    }

    // ── Solving (used to validate puzzles, hints and the generator) ───────────

    /** The mirrors a player can turn, in reading order. */
    public static List<int[]> rotatableMirrors(LaserBoard board) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : board.find(Type.MIRROR)) if (board.get(p[0], p[1]).rotatable) out.add(p);
        return out;
    }

    /**
     * Finds the orientation of every rotatable mirror that solves the board (locks are ignored: the solver
     * only answers "is there a solution"). Returns mirror cell key "x,y" to slash, or null when none exists
     * or there are too many mirrors to search.
     */
    public static Map<String, Boolean> solve(LaserBoard board, int maxSteps) {
        List<int[]> mirrors = rotatableMirrors(board);
        if (mirrors.size() > 16) return null;
        LaserBoard work = board.copy();
        int n = mirrors.size();
        Map<String, Boolean> best = null;
        int bestFlips = Integer.MAX_VALUE;
        for (int mask = 0; mask < (1 << n); mask++) {
            int flips = 0;
            for (int i = 0; i < n; i++) {
                int[] p = mirrors.get(i);
                boolean slash = (mask & (1 << i)) != 0;
                work.get(p[0], p[1]).slash = slash;
                if (slash != board.get(p[0], p[1]).slash) flips++;
            }
            if (flips < bestFlips && solved(work, maxSteps)) {
                bestFlips = flips;
                best = new LinkedHashMap<>();
                for (int[] p : mirrors) best.put(p[0] + "," + p[1], work.get(p[0], p[1]).slash);
            }
        }
        return best;
    }
}
