package nl.kmc.laser;

import nl.kmc.laser.LaserBoard.Comp;
import nl.kmc.laser.LaserBoard.Dir;
import nl.kmc.laser.LaserBoard.Type;

import java.util.*;

/**
 * A puzzle as configured: a board plus its difficulty, point value and time limit. Parsed from plain maps
 * (what the YAML loader gives) so that parsing and validation need no server.
 */
public record PuzzleDef(String id, Difficulty difficulty, LaserBoard board, int points, int timeLimitSeconds) {

    public enum Difficulty { EASY, MEDIUM, HARD }

    public static final int MAX_STEPS = 500;

    /** Parses one puzzle; problems are added to {@code problems} and null is returned when it cannot be built. */
    public static PuzzleDef parse(Map<?, ?> m, int defaultPoints, int defaultSeconds, List<String> problems) {
        String id = String.valueOf(m.get("id"));
        Difficulty diff;
        try { diff = Difficulty.valueOf(String.valueOf(m.get("difficulty")).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { problems.add(id + ": onbekende difficulty " + m.get("difficulty")); return null; }

        int w = 0, h = 0;
        if (m.get("size") instanceof List<?> s && s.size() >= 2 && s.get(0) instanceof Number a && s.get(1) instanceof Number b) {
            w = a.intValue();
            h = b.intValue();
        }
        if (w < 3 || h < 3 || w > 25 || h > 25) { problems.add(id + ": size moet [breedte, hoogte] zijn (3 t/m 25)"); return null; }

        LaserBoard board = new LaserBoard(w, h);
        Set<String> ids = new HashSet<>();
        boolean ok = true;

        if (m.get("walls") instanceof List<?> walls) {
            for (Object o : walls) {
                int[] p = point(o);
                if (p == null || !board.inside(p[0], p[1])) { problems.add(id + ": ongeldige muur " + o); ok = false; continue; }
                Comp c = new Comp();
                c.type = Type.WALL;
                board.set(p[0], p[1], c);
            }
        }
        if (m.get("components") instanceof List<?> comps) {
            for (Object o : comps) {
                if (!(o instanceof Map<?, ?> cm)) { problems.add(id + ": ongeldig onderdeel " + o); ok = false; continue; }
                int[] p = point(cm.get("at"));
                if (p == null || !board.inside(p[0], p[1])) { problems.add(id + ": onderdeel buiten het bord " + cm); ok = false; continue; }
                if (board.get(p[0], p[1]) != null) { problems.add(id + ": twee onderdelen op " + p[0] + "," + p[1]); ok = false; continue; }
                Comp c = new Comp();
                try { c.type = Type.valueOf(String.valueOf(cm.get("type")).toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { problems.add(id + ": onbekend type " + cm.get("type")); ok = false; continue; }
                c.dir = Dir.parse(str(cm.get("dir")), Dir.E);
                if (cm.get("color") != null) c.color = str(cm.get("color")).toUpperCase(Locale.ROOT);
                String orient = str(cm.get("orient"));
                c.slash = orient == null || !orient.toUpperCase(Locale.ROOT).startsWith("BACK");
                c.rotatable = !(cm.get("rotatable") instanceof Boolean b) || b;
                c.id = str(cm.get("id"));
                c.unlockedBy = str(cm.get("unlocked-by"));
                if (cm.get("need-color") != null) c.needColor = str(cm.get("need-color")).toUpperCase(Locale.ROOT);
                if (cm.get("need-dir") != null) c.needDir = Dir.parse(str(cm.get("need-dir")), null);
                if ((c.type == Type.RECEIVER || c.type == Type.MIRROR) && c.id == null)
                    c.id = (c.type == Type.RECEIVER ? "r" : "m") + p[0] + "_" + p[1];
                if (c.id != null && !ids.add(c.id)) { problems.add(id + ": id '" + c.id + "' komt twee keer voor"); ok = false; }
                board.set(p[0], p[1], c);
            }
        }
        if (m.get("required") instanceof List<?> req) for (Object o : req) board.required().add(String.valueOf(o));

        if (!ok) return null;
        int points = m.get("points") instanceof Number n ? n.intValue() : defaultPoints;
        int seconds = m.get("time-limit-seconds") instanceof Number n ? n.intValue() : defaultSeconds;
        return new PuzzleDef(id, diff, board, Math.max(0, points), Math.max(10, seconds));
    }

    /** Everything that makes a puzzle unusable: empty list when it is fine. */
    public List<String> validate() {
        List<String> p = new ArrayList<>();
        if (board.find(Type.EMITTER).isEmpty()) p.add(id + ": geen emitter");
        if (board.find(Type.RECEIVER).isEmpty()) p.add(id + ": geen receiver");
        Set<String> receiverIds = new HashSet<>();
        for (int[] r : board.find(Type.RECEIVER)) receiverIds.add(board.get(r[0], r[1]).id);
        for (String req : board.required()) if (!receiverIds.contains(req)) p.add(id + ": required receiver '" + req + "' bestaat niet");
        for (int[] mm : board.find(Type.MIRROR)) {
            Comp c = board.get(mm[0], mm[1]);
            if (c.unlockedBy != null && !receiverIds.contains(c.unlockedBy))
                p.add(id + ": spiegel " + c.id + " wacht op onbekende receiver '" + c.unlockedBy + "'");
        }
        if (!p.isEmpty()) return p;
        if (LaserSim.solved(board, MAX_STEPS)) p.add(id + ": is al opgelost in de beginstand");
        if (LaserSim.solve(board, MAX_STEPS) == null) p.add(id + ": heeft geen oplossing");
        return p;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** Reads [x, y] or "x,y". */
    private static int[] point(Object o) {
        if (o instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b)
            return new int[]{a.intValue(), b.intValue()};
        if (o instanceof String s) {
            String[] parts = s.split(",");
            if (parts.length == 2) {
                try { return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())}; }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }
}
