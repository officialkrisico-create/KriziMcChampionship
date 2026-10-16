package nl.kmc.laser;

import java.util.*;

/**
 * A puzzle board: a width x height grid of optical components. Coordinates are (x, y) with x to the east and
 * y to the south ({@link Dir#N} is y - 1). Pure data, no Minecraft types, so every rule can be unit-tested.
 */
public final class LaserBoard {

    public enum Type { EMPTY, WALL, EMITTER, MIRROR, SPLITTER, ABSORBER, FILTER, RECEIVER }

    /** The four directions a beam can travel in. */
    public enum Dir {
        N(0, -1), E(1, 0), S(0, 1), W(-1, 0);

        public final int dx, dy;
        Dir(int dx, int dy) { this.dx = dx; this.dy = dy; }
        public Dir left() { return values()[(ordinal() + 3) % 4]; }
        public Dir right() { return values()[(ordinal() + 1) % 4]; }

        public static Dir parse(String s, Dir def) {
            if (s == null) return def;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "N", "NORTH", "UP" -> N;
                case "E", "EAST", "RIGHT" -> E;
                case "S", "SOUTH", "DOWN" -> S;
                case "W", "WEST", "LEFT" -> W;
                default -> def;
            };
        }

        /** The direction after bouncing off a mirror: '/' (slash) or '\' (backslash). */
        public Dir reflect(boolean slash) {
            if (slash) {
                return switch (this) { case E -> N; case N -> E; case W -> S; case S -> W; };
            }
            return switch (this) { case E -> S; case S -> E; case W -> N; case N -> W; };
        }
    }

    /** One component. Fields are only meaningful for the types that use them. */
    public static final class Comp {
        public Type type = Type.EMPTY;
        public Dir dir = Dir.E;            // emitter: the way it shoots
        public String color = "WHITE";     // emitter / filter colour
        public boolean slash = true;       // mirror orientation: '/' or '\'
        public boolean rotatable = true;   // mirror: may players turn it
        public String id;                  // mirrors and receivers have an id
        public String unlockedBy;          // mirror: only turnable while this receiver is active
        public String needColor;           // receiver: required beam colour (null = any)
        public Dir needDir;                // receiver: required travel direction (null = any)

        public Comp copy() {
            Comp c = new Comp();
            c.type = type; c.dir = dir; c.color = color; c.slash = slash; c.rotatable = rotatable;
            c.id = id; c.unlockedBy = unlockedBy; c.needColor = needColor; c.needDir = needDir;
            return c;
        }
    }

    private final int width, height;
    private final Comp[] cells;
    /** Receivers that have to be active for the puzzle to count as solved; empty = all of them. */
    private final Set<String> required = new LinkedHashSet<>();

    public LaserBoard(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.cells = new Comp[this.width * this.height];
    }

    public int width() { return width; }
    public int height() { return height; }
    public boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < width && y < height; }

    /** The component at (x, y); {@code null} for an empty cell. */
    public Comp get(int x, int y) { return inside(x, y) ? cells[y * width + x] : null; }

    public Type typeAt(int x, int y) {
        Comp c = get(x, y);
        return c == null ? Type.EMPTY : c.type;
    }

    public void set(int x, int y, Comp c) {
        if (inside(x, y)) cells[y * width + x] = c;
    }

    public Set<String> required() { return required; }

    public LaserBoard copy() {
        LaserBoard b = new LaserBoard(width, height);
        for (int i = 0; i < cells.length; i++) b.cells[i] = cells[i] == null ? null : cells[i].copy();
        b.required.addAll(required);
        return b;
    }

    /** All components of a type, in reading order. */
    public List<int[]> find(Type t) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if (typeAt(x, y) == t) out.add(new int[]{x, y});
        return out;
    }

    /** One character per cell, one line per row (for the admin's /laser puzzle show). */
    public List<String> toAscii() {
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < width; x++) {
                Comp c = get(x, y);
                char ch = '.';
                if (c != null) {
                    switch (c.type) {
                        case WALL -> ch = '#';
                        case ABSORBER -> ch = 'X';
                        case SPLITTER -> ch = 'S';
                        case FILTER -> ch = 'F';
                        case RECEIVER -> ch = 'R';
                        case MIRROR -> ch = c.slash ? '/' : '\\';
                        case EMITTER -> ch = switch (c.dir) { case N -> '^'; case E -> '>'; case S -> 'v'; case W -> '<'; };
                        default -> { }
                    }
                }
                sb.append(ch);
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    /** Ids of the receivers that count: the required ones, or every receiver when none are listed. */
    public Set<String> goalReceivers() {
        if (!required.isEmpty()) return required;
        Set<String> all = new LinkedHashSet<>();
        for (int[] p : find(Type.RECEIVER)) {
            Comp c = get(p[0], p[1]);
            if (c.id != null) all.add(c.id);
        }
        return all;
    }
}
