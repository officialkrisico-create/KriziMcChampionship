package nl.kmc.kmccore.host;

import nl.kmc.kmccore.host.HostDialogues.Stage;
import nl.kmc.kmccore.host.HostQueue.SpeechLine;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BinaryOperator;

/**
 * The arithmetic behind a staged speech (no Bukkit world needed): when every line is on screen, where the
 * host walks, how tense each moment is and where the camera goes. {@link HostDirector} turns the result into
 * real locations, a camera route and scheduled actions.
 *
 * <p>Everything is expressed in ticks since the start of the speech — the same clock {@code HostManager}
 * uses to show the lines, so camera, host and bubble stay in step.
 */
public final class SceneMath {

    private SceneMath() { }

    public static final List<String> SHOTS = List.of("wide", "push", "closeup", "orbit", "crane", "fly", "top",
            "low", "hold", "none");
    public static final Set<String> ACTS = Set.of("jump", "hop", "swing", "wave", "crouch", "stand", "turn",
            "spin", "nod");
    public static final Set<String> FX = Set.of("thunder", "burst", "rise", "stomp");

    /** Height of the host's head above its feet (where close shots look). */
    public static final double HEAD = 1.65;
    /** Height of the host's chest (where wide shots look). */
    public static final double BODY = 1.1;

    // ── Timing ───────────────────────────────────────────────────────────────

    /** The tick at which each line appears (the same arithmetic as HostManager's playback chain). */
    public static long[] displayTicks(List<SpeechLine> lines) {
        long[] out = new long[lines.size()];
        long t = 0;
        for (int i = 0; i < out.length; i++) {
            SpeechLine l = lines.get(i);
            t += Math.max(0, l.delayTicks());
            out[i] = t;
            t += Math.max(10, l.holdTicks());
        }
        return out;
    }

    /** The tick at which each line is gone again (the next line's wait starts here). */
    public static long[] endTicks(List<SpeechLine> lines) {
        long[] start = displayTicks(lines);
        long[] out = new long[start.length];
        for (int i = 0; i < out.length; i++) out[i] = start[i] + Math.max(10, lines.get(i).holdTicks());
        return out;
    }

    public static long totalTicks(List<SpeechLine> lines) {
        long[] e = endTicks(lines);
        return e.length == 0 ? 0 : e[e.length - 1];
    }

    // ── Tension ──────────────────────────────────────────────────────────────

    /**
     * How tense each line is, 0..1. A line with {@code tension: N} (0-10) is N/10; the rest rise steadily from
     * a calm start to the end of the speech.
     */
    public static double[] tension(List<SpeechLine> lines) {
        int n = lines.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Stage st = lines.get(i).stage();
            if (st != null && st.tension() >= 0) out[i] = Math.min(1.0, st.tension() / 10.0);
            else out[i] = n <= 1 ? 0.5 : 0.12 + 0.88 * i / (n - 1);
        }
        return out;
    }

    /** The tension at {@code tick}, interpolated between the lines' own values. */
    public static double tensionAt(long tick, long[] display, double[] tension) {
        if (display.length == 0) return 0;
        if (tick <= display[0]) return tension[0] * Math.max(0, Math.min(1, tick / (double) Math.max(1, display[0])));
        for (int i = 0; i + 1 < display.length; i++) {
            if (tick < display[i + 1]) {
                double f = (tick - display[i]) / (double) Math.max(1, display[i + 1] - display[i]);
                return tension[i] + (tension[i + 1] - tension[i]) * f;
            }
        }
        return tension[tension.length - 1];
    }

    // ── Walking ──────────────────────────────────────────────────────────────

    public record Walk(String kind, double blocks) { }

    /** {@code forward:4}, {@code back}, {@code left:2.5}, {@code right}, {@code home}; null when it is none of these. */
    public static Walk parseWalk(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.trim().toLowerCase(Locale.ROOT).split("[:\\s]+");
        String kind = p[0];
        if (!Set.of("forward", "back", "left", "right", "home").contains(kind)) return null;
        double n = kind.equals("home") ? 0 : 3;
        if (p.length > 1) {
            try { n = Double.parseDouble(p[1].replace(',', '.')); }
            catch (NumberFormatException e) { return null; }
        }
        return new Walk(kind, Math.max(0, Math.min(30, n)));
    }

    /** The unit vector to the right of {@code dir} (both flat). */
    public static Vector right(Vector dir) {
        return new Vector(-dir.getZ(), 0, dir.getX());
    }

    /** Where a walk from {@code from} ends. {@code dir} is the way the host faces at its spot. */
    public static Vector walkTarget(Vector from, Vector home, Vector dir, Walk w) {
        Vector out = from.clone();
        switch (w.kind()) {
            case "forward" -> out.add(dir.clone().multiply(w.blocks()));
            case "back" -> out.subtract(dir.clone().multiply(w.blocks()));
            case "left" -> out.subtract(right(dir).multiply(w.blocks()));
            case "right" -> out.add(right(dir).multiply(w.blocks()));
            case "home" -> out = home.clone();
            default -> { }
        }
        return out;
    }

    /** One planned walk: the host goes from {@code from} to {@code to} between ticks {@code t0} and {@code t1}. */
    public record Move(long t0, long t1, Vector from, Vector to) { }

    /**
     * Plans the host's walks, one after another. A walk starts when its line appears (or when the previous
     * walk has finished) and lasts as long as the distance needs at {@code speed} blocks per tick.
     *
     * @param resolver (from, wanted) → a real standing place: no wall, no edge, on the ground; null = as is
     */
    public static List<Move> planWalks(List<SpeechLine> lines, long[] display, Vector home, Vector dir,
                                       double speed, BinaryOperator<Vector> resolver) {
        List<Move> moves = new ArrayList<>();
        Vector at = home.clone();
        long free = 0;
        double v = Math.max(0.05, speed);
        for (int i = 0; i < lines.size(); i++) {
            Stage st = lines.get(i).stage();
            if (st == null || st.walk().isEmpty()) continue;
            Walk w = parseWalk(st.walk());
            if (w == null) continue;
            Vector to = walkTarget(at, home, dir, w);
            if (resolver != null) to = resolver.apply(at, to);
            double dist = Math.hypot(to.getX() - at.getX(), to.getZ() - at.getZ());
            if (dist < 0.3) continue;
            long t0 = Math.max(display[i], free);
            long t1 = t0 + Math.max(2, Math.round(dist / v));
            moves.add(new Move(t0, t1, at.clone(), to.clone()));
            at = to;
            free = t1;
        }
        return moves;
    }

    /** Where the host is at {@code tick}, following {@code moves} from {@code home}. */
    public static Vector hostAt(long tick, List<Move> moves, Vector home) {
        Vector at = home;
        for (Move m : moves) {
            if (tick < m.t0()) return at.clone();
            if (tick < m.t1()) {
                double f = (tick - m.t0()) / (double) Math.max(1, m.t1() - m.t0());
                return m.from().clone().add(m.to().clone().subtract(m.from()).multiply(f));
            }
            at = m.to();
        }
        return at.clone();
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    /** A camera key: at tick {@code t} the camera stands at {@code pos} and looks at {@code look}. */
    public record Key(long t, Vector pos, Vector look) { }

    private static Vector p(Vector host, Vector dir, double ahead, double side, double up) {
        return host.clone().add(dir.clone().multiply(ahead)).add(right(dir).multiply(side)).add(new Vector(0, up, 0));
    }

    private static Vector at(Vector host, double up) {
        return host.clone().add(new Vector(0, up, 0));
    }

    /**
     * The camera keys of one shot between {@code t0} and {@code t1}; the last one is at {@code t1}. The shots
     * are defined relative to the host and the way it faces, so they work wherever the host is placed.
     *
     * @param hostAt the host's position at a tick
     * @param variant alternates the side the camera swings to, so two equal shots in a row do not mirror
     */
    public static List<Key> shot(String shot, long t0, long t1, java.util.function.LongFunction<Vector> hostAt,
                                 Vector dir, int variant) {
        List<Key> k = new ArrayList<>();
        long d = Math.max(2, t1 - t0);
        double s = variant % 2 == 0 ? 1 : -1;
        Vector h1 = hostAt.apply(t1);
        long tm = t0 + d / 2;
        Vector hm = hostAt.apply(tm);
        switch (shot) {
            case "wide" -> {
                k.add(new Key(tm, p(hm, dir, 14, 3 * s, 6.0), at(hm, BODY)));
                k.add(new Key(t1, p(h1, dir, 12.5, 5 * s, 5.0), at(h1, BODY)));
            }
            case "push" -> {
                k.add(new Key(tm, p(hm, dir, 8.0, 1.5 * s, 2.8), at(hm, HEAD)));
                k.add(new Key(t1, p(h1, dir, 4.6, 0, 1.9), at(h1, HEAD)));
            }
            case "closeup" -> {
                k.add(new Key(tm, p(hm, dir, 4.2, 0.6 * s, 1.9), at(hm, HEAD)));
                k.add(new Key(t1, p(h1, dir, 2.5, 0.4 * s, 1.7), at(h1, HEAD)));
            }
            case "orbit" -> {
                double a0 = Math.atan2(dir.getZ(), dir.getX());
                for (int i = 1; i <= 4; i++) {
                    double f = i / 4.0;
                    long t = t0 + Math.round(d * f);
                    Vector h = hostAt.apply(t);
                    double th = a0 + s * Math.toRadians(-55 + 110 * f);
                    double r = 6.2 - 1.2 * f;
                    k.add(new Key(t, h.clone().add(new Vector(Math.cos(th) * r, 2.4 + 0.6 * f, Math.sin(th) * r)),
                            at(h, BODY + 0.3)));
                }
            }
            case "crane" -> {
                k.add(new Key(tm, p(hm, dir, 5.5, 0, 4.5), at(hm, BODY)));
                k.add(new Key(t1, p(h1, dir, 9.0, 1.5 * s, 10.5), at(h1, 0.6)));
            }
            case "fly" -> {
                long ta = t0 + Math.round(d * 0.40);
                long tb = t0 + Math.round(d * 0.75);
                Vector ha = hostAt.apply(ta), hb = hostAt.apply(tb);
                k.add(new Key(ta, p(ha, dir, 34, 8 * s, 17), at(ha, BODY)));
                k.add(new Key(tb, p(hb, dir, 17, -3 * s, 8), at(hb, BODY)));
                k.add(new Key(t1, p(h1, dir, 6.5, 0, 2.8), at(h1, HEAD)));
            }
            case "top" -> {
                k.add(new Key(tm, p(hm, dir, 4, 0, 8), at(hm, 0.5)));
                k.add(new Key(t1, p(h1, dir, 0.8, 0, 14), at(h1, 0.4)));
            }
            case "low" -> {
                k.add(new Key(tm, p(hm, dir, 5.5, 1.5 * s, 0.9), at(hm, HEAD + 0.3)));
                k.add(new Key(t1, p(h1, dir, 4.0, 0.5 * s, 0.4), at(h1, HEAD + 0.5)));
            }
            default -> { }                        // "hold": the camera stays where it is
        }
        return k;
    }

    /** The last page of the line that starts at {@code i} (a line split over pages is one group). */
    public static int groupEnd(List<SpeechLine> lines, int i) {
        int j = i;
        while (j + 1 < lines.size() && lines.get(j + 1).stage() != null && lines.get(j + 1).stage().isContinuation()) j++;
        return j;
    }

    /**
     * The camera for a whole speech: one shot per line, glued into one continuous path. Line {@code i} owns the
     * time from the end of line {@code i-1} (its wait included) to its own end, so the camera starts moving
     * toward the next shot while the host is still pausing. A line split over several pages is one shot, and a
     * line without a shot keeps the previous one.
     *
     * <p>The first key is at tick 0, the last at {@code totalTicks}; times strictly increase.
     */
    public static List<Key> camera(List<SpeechLine> lines, java.util.function.LongFunction<Vector> hostAt, Vector dir) {
        long[] end = endTicks(lines);
        List<Key> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        String current = "";
        int variant = 0;
        long segStart = 0;
        int i = 0;
        while (i < lines.size()) {
            int j = groupEnd(lines, i);
            long segEnd = end[j];
            Stage st = lines.get(i).stage();
            String shot = st != null && !st.shot().isEmpty() && !st.isContinuation() ? st.shot()
                    : (current.isEmpty() ? "push" : current);
            if (!shot.equals("hold")) current = shot;
            List<Key> keys = shot(shot, segStart, segEnd, hostAt, dir, variant++);
            if (keys.isEmpty() && !out.isEmpty()) {
                // hold: keep the last position until the end of this line
                Key last = out.get(out.size() - 1);
                keys = List.of(new Key(segEnd, last.pos().clone(), at(hostAt.apply(segEnd), BODY)));
            }
            for (Key key : keys) {
                if (!out.isEmpty() && key.t() <= out.get(out.size() - 1).t()) continue;   // strictly increasing
                out.add(key);
            }
            segStart = segEnd;
            i = j + 1;
        }
        if (out.isEmpty()) {
            Vector h = hostAt.apply(0);
            out.add(new Key(Math.max(2, end[end.length - 1]), p(h, dir, 6, 0, 2.5), at(h, HEAD)));
        }
        // the very first key opens the shot: the camera starts a little further out, so it moves in from the start
        Key first = out.get(0);
        Vector h0 = hostAt.apply(0);
        Vector start = h0.clone().add(first.pos().clone().subtract(h0).multiply(1.5)).add(new Vector(0, 1.5, 0));
        out.add(0, new Key(0, start, first.look().clone()));
        return out;
    }
}
