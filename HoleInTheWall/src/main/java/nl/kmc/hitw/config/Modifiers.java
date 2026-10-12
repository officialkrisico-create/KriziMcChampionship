package nl.kmc.hitw.config;

import java.util.Map;

/** The combined effect of every currently active {@link EventDef}. */
public record Modifiers(double speedMult, double spacingMult, double gapScale, double speedBonus,
                        Map<String, Double> patternWeights) {

    public static final Modifiers NONE = new Modifiers(1, 1, 1, 0, Map.of());
}
