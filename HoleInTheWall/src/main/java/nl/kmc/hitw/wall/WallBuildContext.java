package nl.kmc.hitw.wall;

import java.util.Random;

/**
 * Everything a {@link WallShapeBuilder} needs to know about the moment a wall is created.
 *
 * @param halfWidth lateral half-extent of the wall (covers the whole platform)
 * @param anchorU   sideways position (in the wall's frame) the players are clustered around, or where
 *                  the previous gap is — new gaps are placed near it so every wall stays reachable
 * @param reach     how far (blocks) from {@code anchorU} the main gap may be placed — derived from how
 *                  long players get before the wall arrives
 * @param maxAbsU   gaps are kept inside {@code [-maxAbsU, +maxAbsU]} (the platform's usable width)
 * @param gapScale  multiplier on every gap width (events shrink holes with this)
 */
public record WallBuildContext(double halfWidth, double anchorU, double reach, double maxAbsU,
                               double gapScale, Random rnd) {
}
