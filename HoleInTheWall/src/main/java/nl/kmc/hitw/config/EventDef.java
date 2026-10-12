package nl.kmc.hitw.config;

import java.util.Map;

/**
 * A temporary twist during a round — everything is a parameter, so "new events" are config entries.
 *
 * @param duration       seconds the effect lasts ({@code 0} = instant: only {@code speedBonus} is applied, permanently)
 * @param speedMult      multiplies wall speed while active
 * @param spacingMult    multiplies the distance between walls while active (below 1 = walls come closer together)
 * @param gapScale       multiplies every gap width while active (below 1 = smaller holes)
 * @param speedBonus     blocks/second added to the wall speed; for instant events it stays for the rest of the round
 * @param patternWeights per-pattern multipliers on how likely each pattern is while active (e.g. more low/high walls)
 */
public record EventDef(String id, double weight, double duration, String title, String subtitle, String sound,
                       double speedMult, double spacingMult, double gapScale, double speedBonus,
                       Map<String, Double> patternWeights) {
}
