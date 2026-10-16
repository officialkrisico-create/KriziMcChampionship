package nl.kmc.blockball;

import nl.kmc.blockball.BallPhysics.Box;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The goalkeeper rules that need no server: who becomes keeper, where a keeper may use his hands, which
 * balls can be caught, and what counts as a save.
 */
public final class KeeperLogic {

    private KeeperLogic() { }

    /**
     * The keeper of a side: the candidate who has been keeper the fewest times so far (so everybody gets a
     * turn); ties go to the first in the list. Null when the team is too small to play with a keeper.
     */
    public static UUID pick(List<UUID> candidates, Map<UUID, Integer> stints, int minTeamSize) {
        if (candidates == null || candidates.isEmpty() || candidates.size() < Math.max(1, minTeamSize)) return null;
        UUID best = null;
        int bestN = Integer.MAX_VALUE;
        for (UUID id : candidates) {
            int n = stints.getOrDefault(id, 0);
            if (n < bestN) { bestN = n; best = id; }
        }
        return best;
    }

    /** The keeper's area: the goal widened by {@code depth} blocks on every side, kept inside the field. */
    public static Box zone(Box goal, Box field, double depth) {
        return new Box(Math.max(field.minX(), goal.minX() - depth), field.minY(),
                Math.max(field.minZ(), goal.minZ() - depth),
                Math.min(field.maxX(), goal.maxX() + depth), field.maxY(),
                Math.min(field.maxZ(), goal.maxZ() + depth));
    }

    public static boolean inZone(Box zone, double x, double z) {
        return x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ();
    }

    /** True when the ball is moving towards the point (gx, gz) (its horizontal velocity points at it). */
    public static boolean headingTo(double bx, double bz, double vx, double vz, double gx, double gz) {
        return vx * (gx - bx) + vz * (gz - bz) > 0;
    }

    /** A ball can be caught when it is slow enough and at a height the keeper can reach. */
    public static boolean canCatch(double speed, double maxSpeed, double ballY, double keeperFeetY, double reachHeight) {
        return speed <= maxSpeed && ballY >= keeperFeetY - 0.5 && ballY <= keeperFeetY + reachHeight;
    }

    /**
     * A save: the keeper stops (catch or punch) a ball that an opponent played recently, that is moving
     * fast enough to be a real shot and that is heading for his goal.
     *
     * @param lastTouchSide   side of the last touch of the ball (-1 = nobody)
     * @param ticksSinceTouch how long ago that touch was
     */
    public static boolean isSave(int lastTouchSide, int keeperSide, long ticksSinceTouch, long windowTicks,
                                 double speed, double minSpeed, boolean headingToGoal) {
        return lastTouchSide >= 0 && lastTouchSide != keeperSide
                && ticksSinceTouch <= windowTicks && speed >= minSpeed && headingToGoal;
    }
}
