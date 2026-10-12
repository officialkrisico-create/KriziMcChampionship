package nl.kmc.hitw.managers;

import nl.kmc.hitw.config.EventDef;
import nl.kmc.hitw.config.Modifiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Triggers the configured special events during a round and combines whatever is active into {@link Modifiers}. */
public final class EventScheduler {

    private record Active(EventDef def, double endsAt) {}

    private final List<EventDef> defs;
    private final boolean enabled;
    private final double startAfter, minGap, maxGap;
    private final Random rnd;

    private final List<Active> active = new ArrayList<>();
    private double nextAt;
    private double permanentBonus;

    public EventScheduler(List<EventDef> defs, boolean enabled, double startAfter, double minGap, double maxGap, Random rnd) {
        this.defs = defs;
        this.enabled = enabled;
        this.startAfter = startAfter;
        this.minGap = minGap;
        this.maxGap = Math.max(minGap, maxGap);
        this.rnd = rnd;
        reset();
    }

    public void reset() {
        active.clear();
        permanentBonus = 0;
        nextAt = startAfter;
    }

    /**
     * Advance to round time {@code t} (seconds). Returns the event that just started, or {@code null}.
     * Events never overlap: the next one is only scheduled after the current one has ended.
     */
    public EventDef update(double t) {
        active.removeIf(a -> t >= a.endsAt());
        if (!enabled || defs.isEmpty() || t < nextAt || !active.isEmpty()) return null;

        EventDef def = pickWeighted();
        if (def == null) return null;
        if (def.duration() > 0) active.add(new Active(def, t + def.duration()));
        else permanentBonus += def.speedBonus();
        nextAt = t + def.duration() + minGap + rnd.nextDouble() * (maxGap - minGap);
        return def;
    }

    public boolean hasActive() { return !active.isEmpty(); }

    public List<EventDef> activeEvents() {
        List<EventDef> out = new ArrayList<>();
        for (Active a : active) out.add(a.def());
        return out;
    }

    /** Combined effect of everything running right now (plus any permanent speed bonus from instant events). */
    public Modifiers modifiers() {
        double speed = 1, spacing = 1, gap = 1, bonus = permanentBonus;
        Map<String, Double> weights = new HashMap<>();
        for (Active a : active) {
            EventDef d = a.def();
            speed *= d.speedMult();
            spacing *= d.spacingMult();
            gap *= d.gapScale();
            bonus += d.speedBonus();
            d.patternWeights().forEach((k, v) -> weights.merge(k, v, (x, y) -> x * y));
        }
        return new Modifiers(speed, spacing, gap, bonus, weights);
    }

    private EventDef pickWeighted() {
        double total = 0;
        for (EventDef d : defs) total += Math.max(0, d.weight());
        if (total <= 0) return null;
        double roll = rnd.nextDouble() * total;
        for (EventDef d : defs) {
            roll -= Math.max(0, d.weight());
            if (roll <= 0) return d;
        }
        return defs.get(defs.size() - 1);
    }
}
