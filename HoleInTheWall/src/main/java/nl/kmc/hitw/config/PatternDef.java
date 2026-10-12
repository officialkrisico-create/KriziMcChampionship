package nl.kmc.hitw.config;

import java.util.List;

/**
 * A named run of walls, e.g. "alternating_height" = LOW, HIGH, LOW, HIGH, NORMAL. One pattern always
 * comes from a single direction; the next pattern picks a new one.
 *
 * @param rotationDegPerSec how fast the walls of this pattern swing around the arena centre (0 = straight on;
 *                          the sign is randomised per pattern)
 * @param speedMult         extra speed multiplier for every wall in the pattern
 */
public record PatternDef(String name, double weight, double minTime, double maxTime,
                         double rotationDegPerSec, double speedMult, List<Step> steps) {

    public record Step(String typeId, int count) {}
}
