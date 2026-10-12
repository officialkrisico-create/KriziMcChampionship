package nl.kmc.hitw.wall;

/**
 * Configuration of one wall type, straight from {@code wall-types:} in config.yml. Everything a type
 * does is described by data, so a new variant is usually just a new config entry:
 *
 * <ul>
 *   <li>{@code shape: HOLES} — a full wall with {@code holes} openings of {@code gapWidth} blocks
 *       (NORMAL = 1, DOUBLE_HOLE = 2, SPLIT = several narrow ones)</li>
 *   <li>{@code shape: BAR} — one solid bar from {@code yMin} to {@code yMax} across the whole width
 *       (LOW = jump over it, HIGH = sneak under it)</li>
 * </ul>
 * New shapes can be added in code with {@link WallTypes#registerShape}.
 */
public record WallTypeDef(String id, String shape, String displayName, String material,
                          int holes, double gapWidth, double height,
                          double yMin, double yMax, double thickness, double speedMult) {
}
