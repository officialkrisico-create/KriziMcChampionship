package nl.kmc.hitw.wall;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides where a wall's gaps go. The first gap (index 0) is the "reachable" one: it is placed within
 * {@link WallBuildContext#reach()} of {@link WallBuildContext#anchorU()}, so a player standing where
 * the previous wall left them can always make it. Any further gaps go anywhere on the platform,
 * at least {@code minSep} blocks away from each other, which just gives players extra choice.
 */
public final class GapPlacer {

    private GapPlacer() {}

    public static List<Double> place(int count, double width, WallBuildContext ctx, double minSep) {
        double maxCenter = Math.max(0, ctx.maxAbsU() - width / 2);
        double lo = Math.max(-maxCenter, ctx.anchorU() - ctx.reach());
        double hi = Math.min(maxCenter, ctx.anchorU() + ctx.reach());
        if (lo > hi) {                       // anchor is outside the usable range: pin to the nearest edge
            double pinned = Math.max(-maxCenter, Math.min(maxCenter, ctx.anchorU()));
            lo = hi = pinned;
        }

        List<Double> centers = new ArrayList<>();
        centers.add(lo + ctx.rnd().nextDouble() * (hi - lo));

        for (int attempt = 0; centers.size() < count && attempt < 60; attempt++) {
            double candidate = -maxCenter + ctx.rnd().nextDouble() * (2 * maxCenter);
            boolean clear = true;
            for (double existing : centers) {
                if (Math.abs(candidate - existing) < width + minSep) { clear = false; break; }
            }
            if (clear) centers.add(candidate);
        }
        return centers;
    }
}
