package nl.kmc.hitw.wall;

import java.util.List;

/**
 * The finished cross-section of one wall.
 *
 * @param typeId      which {@link WallTypeDef} produced it (e.g. {@code "NORMAL"})
 * @param displayName shown on the scoreboard / titles ("Next Wall: FAST")
 * @param material    block used to draw the wall (a Bukkit material name — kept as text so this
 *                    class stays free of server classes)
 * @param solids      the solid rectangles (see {@link Rect})
 * @param halfWidth   half the wall's total width; the wall spans {@code u ∈ [-halfWidth, +halfWidth]}
 * @param thickness   depth of the wall along its travel direction
 * @param speedMult   how much faster/slower than the round's current wall speed this wall moves
 * @param gapCenter   sideways centre of the main gap, or {@link Double#NaN} if the wall has none
 *                    (low/high bars) — used to keep consecutive gaps reachable
 */
public record WallShape(String typeId, String displayName, String material, List<Rect> solids,
                        double halfWidth, double thickness, double speedMult, double gapCenter) {

    public boolean hasGap() { return !Double.isNaN(gapCenter); }

    /** Highest point of any solid, above the platform surface — used to size displays/clearing. */
    public double maxHeight() {
        double h = 0;
        for (Rect r : solids) h = Math.max(h, r.y1());
        return h;
    }
}
