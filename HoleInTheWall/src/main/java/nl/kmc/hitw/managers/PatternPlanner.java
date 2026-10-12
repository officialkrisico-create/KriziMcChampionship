package nl.kmc.hitw.managers;

import nl.kmc.hitw.config.PatternDef;
import nl.kmc.hitw.wall.WallSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plans the wall sequence of a round: picks weighted patterns, gives each its own approach direction
 * (so players have to re-orient between patterns, not between every wall), and always knows the next
 * wall — which is what the scoreboard's "Next Wall" shows.
 */
public final class PatternPlanner {

    private final List<PatternDef> patterns;
    private final List<Double> directionsDeg;
    private final Random rnd;
    private final Deque<WallSpec> queue = new ArrayDeque<>();

    private String lastPattern = "";
    private String currentPattern = "";
    private double lastAngleDeg = Double.NaN;

    public PatternPlanner(List<PatternDef> patterns, List<Double> directionsDeg, Random rnd) {
        this.patterns = patterns.isEmpty()
                ? List.of(new PatternDef("fallback", 1, 0, 1e9, 0, 1, List.of(new PatternDef.Step("NORMAL", 3))))
                : patterns;
        this.directionsDeg = directionsDeg.isEmpty() ? List.of(0.0) : directionsDeg;
        this.rnd = rnd;
    }

    /** The next wall, without consuming it. {@code weightMods} are an event's per-pattern likelihood multipliers. */
    public WallSpec peek(double t, Map<String, Double> weightMods) {
        ensure(t, weightMods);
        return queue.peekFirst();
    }

    /** The next wall, consuming it. */
    public WallSpec poll(double t, Map<String, Double> weightMods) {
        ensure(t, weightMods);
        return queue.pollFirst();
    }

    /** Name of the pattern the most recently planned walls belong to. */
    public String currentPattern() { return currentPattern; }

    private void ensure(double t, Map<String, Double> mods) {
        if (queue.isEmpty()) plan(t, mods);
    }

    private void plan(double t, Map<String, Double> mods) {
        List<PatternDef> pool = new ArrayList<>();
        for (PatternDef p : patterns) if (t >= p.minTime() && t <= p.maxTime()) pool.add(p);
        if (pool.isEmpty()) pool.addAll(patterns);
        if (pool.size() > 1) pool.removeIf(p -> p.name().equals(lastPattern));

        double total = 0;
        double[] weights = new double[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            weights[i] = Math.max(0, pool.get(i).weight()) * mods.getOrDefault(pool.get(i).name(), 1.0);
            total += weights[i];
        }
        PatternDef chosen = pool.get(0);
        if (total > 0) {
            double roll = rnd.nextDouble() * total;
            for (int i = 0; i < weights.length; i++) {
                roll -= weights[i];
                if (roll <= 0) { chosen = pool.get(i); break; }
            }
        }

        double angleDeg = pickDirection();
        double rotation = Math.toRadians(chosen.rotationDegPerSec()) * (rnd.nextBoolean() ? 1 : -1);
        for (PatternDef.Step step : chosen.steps()) {
            for (int i = 0; i < step.count(); i++) {
                queue.add(new WallSpec(step.typeId(), Math.toRadians(angleDeg), rotation, chosen.speedMult()));
            }
        }
        lastPattern = currentPattern = chosen.name();
        lastAngleDeg = angleDeg;
    }

    private double pickDirection() {
        if (directionsDeg.size() == 1) return directionsDeg.get(0);
        double d;
        int guard = 0;
        do { d = directionsDeg.get(rnd.nextInt(directionsDeg.size())); }
        while (d == lastAngleDeg && ++guard < 20);
        return d;
    }
}
