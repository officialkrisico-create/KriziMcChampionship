package nl.kmc.hitw.wall;

import org.bukkit.entity.BlockDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * One live wall. {@code s} is its signed distance from the arena centre along its normal (it starts
 * far out on the positive side and travels through the centre to the negative side); {@code alpha} is
 * the direction of that normal. The previous tick's values are kept so collision can sweep the move.
 */
public final class Wall {

    public final int id;
    public final WallShape shape;
    public final WallSpec spec;
    /** Wall type multiplier × pattern multiplier. */
    public final double speedFactor;
    /** Swing around the arena centre, radians per second. */
    public final double omega;

    public double s, prevS;
    public double alpha, prevAlpha;

    /** Set once the wall has fully passed over the platform (used to credit "walls survived"). */
    public boolean clearedPlatform;

    private final List<BlockDisplay> displays = new ArrayList<>();

    /** The entities drawing this wall (one per solid rectangle, same order as {@link WallShape#solids()}). */
    public List<BlockDisplay> displaysMutable() { return displays; }

    public Wall(int id, WallShape shape, WallSpec spec, double startS) {
        this.id = id;
        this.shape = shape;
        this.spec = spec;
        this.speedFactor = shape.speedMult() * spec.speedMult();
        this.omega = spec.rotationRadPerSec();
        this.s = this.prevS = startS;
        this.alpha = this.prevAlpha = spec.angleRad();
    }

    /** Seconds until this wall reaches signed distance {@code atS} (0 if already past it). */
    public double secondsUntil(double atS, double baseSpeed) {
        double v = Math.max(0.01, baseSpeed * speedFactor);
        return Math.max(0, (s - atS) / v);
    }
}
