package nl.kmc.laser;

import nl.kmc.laser.LaserBoard.Comp;
import nl.kmc.laser.LaserBoard.Dir;
import nl.kmc.laser.LaserBoard.Type;

import java.util.*;

/**
 * Generates a puzzle by building its solution first: a beam path with a given number of turns from the left
 * edge to a receiver. Only then are the mirrors scrambled and decoy walls added, and the result is accepted
 * only when the simulation confirms "solved with the solution, not solved as given". So a generated puzzle
 * always has a valid solution.
 */
public final class PuzzleGenerator {

    private PuzzleGenerator() { }

    /** @return a validated puzzle, or null when no layout was found in a reasonable number of tries. */
    public static PuzzleDef generate(String id, PuzzleDef.Difficulty diff, int width, int height, int turns,
                                     int points, int seconds, Random rnd) {
        for (int attempt = 0; attempt < 80; attempt++) {
            LaserBoard b = new LaserBoard(width, height);
            List<int[]> mirrors = new ArrayList<>();
            boolean[] solutionSlash = new boolean[Math.max(1, turns)];
            if (!buildPath(b, turns, rnd, mirrors, solutionSlash)) continue;

            // decoys: walls on cells the solution path does not touch (the solved board proves they are harmless)
            LaserBoard solved = b.copy();
            LaserSim.Result sr = LaserSim.trace(solved, PuzzleDef.MAX_STEPS);
            if (!LaserSim.solved(solved, sr)) continue;
            Set<String> onPath = new HashSet<>();
            for (LaserSim.Seg s : sr.segments) onPath.add(s.x() + "," + s.y());
            int walls = 1 + turns / 2;
            for (int i = 0, tries = 0; i < walls && tries < 40; tries++) {
                int x = rnd.nextInt(width), y = rnd.nextInt(height);
                if (b.get(x, y) != null || onPath.contains(x + "," + y)) continue;
                Comp w = new Comp();
                w.type = Type.WALL;
                b.set(x, y, w);
                i++;
            }
            // scramble: every mirror turned at random; at least one has to be wrong
            for (int[] m : mirrors) b.get(m[0], m[1]).slash = rnd.nextBoolean();
            if (LaserSim.solved(b, PuzzleDef.MAX_STEPS)) {
                int[] m = mirrors.get(rnd.nextInt(mirrors.size()));
                b.get(m[0], m[1]).slash = !b.get(m[0], m[1]).slash;
            }
            PuzzleDef def = new PuzzleDef(id, diff, b, points, seconds);
            if (def.validate().isEmpty()) return def;
        }
        return null;
    }

    private static boolean buildPath(LaserBoard b, int turns, Random rnd, List<int[]> mirrors, boolean[] slashOut) {
        int w = b.width(), h = b.height();
        int x = 0, y = 1 + rnd.nextInt(Math.max(1, h - 2));
        Dir dir = Dir.E;
        Comp em = new Comp();
        em.type = Type.EMITTER;
        em.dir = dir;
        em.color = "RED";
        b.set(x, y, em);
        Set<String> used = new HashSet<>();
        used.add(x + "," + y);

        for (int t = 0; t < turns; t++) {
            int max = 0;
            int cx = x, cy = y;
            while (true) {
                int nx = cx + dir.dx, ny = cy + dir.dy;
                if (!b.inside(nx, ny) || used.contains(nx + "," + ny)) break;
                cx = nx; cy = ny; max++;
            }
            if (max < 2) return false;
            int len = 2 + rnd.nextInt(max - 1);          // 2 .. max cells ahead; the last one holds the mirror
            for (int i = 1; i < len; i++) used.add((x + dir.dx * i) + "," + (y + dir.dy * i));
            int mx = x + dir.dx * len, my = y + dir.dy * len;
            Dir out = rnd.nextBoolean() ? dir.left() : dir.right();
            // the turn is made by a '/' or a '\': the one that sends the beam from `dir` to `out`
            boolean slash = dir.reflect(true) == out;
            if (!slash && dir.reflect(false) != out) return false;
            Comp m = new Comp();
            m.type = Type.MIRROR;
            m.slash = slash;
            m.id = "m" + (t + 1);
            b.set(mx, my, m);
            used.add(mx + "," + my);
            mirrors.add(new int[]{mx, my});
            if (t < slashOut.length) slashOut[t] = slash;
            x = mx; y = my; dir = out;
        }
        // the last stretch ends in the receiver
        int cx = x, cy = y, len = 0;
        while (true) {
            int nx = cx + dir.dx, ny = cy + dir.dy;
            if (!b.inside(nx, ny) || used.contains(nx + "," + ny)) break;
            cx = nx; cy = ny; len++;
        }
        if (len < 1) return false;
        int stop = 1 + rnd.nextInt(len);
        int rx = x + dir.dx * stop, ry = y + dir.dy * stop;
        for (int i = 1; i < stop; i++) used.add((x + dir.dx * i) + "," + (y + dir.dy * i));
        Comp r = new Comp();
        r.type = Type.RECEIVER;
        r.id = "r1";
        b.set(rx, ry, r);
        return !mirrors.isEmpty() || turns == 0;
    }
}
