package nl.kmc.inferno.model;

/**
 * Height of the lava surface over time: it waits {@code delay} seconds, then rises from {@code startY} to
 * {@code endY} over {@code duration} seconds along {@code t^exponent} (exponent above 1 = slow start,
 * fast finish). Plain maths so it can be unit-tested without a server.
 */
public final class LavaModel {

    private final double startY;
    private final double endY;
    private final double delay;
    private final double duration;
    private final double exponent;

    public LavaModel(double startY, double endY, double delaySeconds, double durationSeconds, double exponent) {
        this.startY = startY;
        this.endY = Math.max(startY, endY);
        this.delay = Math.max(0, delaySeconds);
        this.duration = Math.max(1, durationSeconds);
        this.exponent = Math.max(0.1, exponent);
    }

    public double startY() { return startY; }
    public double endY() { return endY; }
    public double delaySeconds() { return delay; }
    public double durationSeconds() { return duration; }

    /** Lava surface height {@code seconds} after the climb started. */
    public double yAt(double seconds) {
        if (seconds <= delay) return startY;
        double t = Math.min(1.0, (seconds - delay) / duration);
        return startY + (endY - startY) * Math.pow(t, exponent);
    }

    /** Seconds after the climb started at which the lava reaches {@code y} (infinite beyond the end). */
    public double secondsUntil(double y) {
        if (y <= startY) return 0;
        if (y > endY) return Double.POSITIVE_INFINITY;
        double frac = (y - startY) / (endY - startY);
        return delay + duration * Math.pow(frac, 1.0 / exponent);
    }

    /** How fast the lava rises at this moment, in blocks per second (0 during the delay and after the end). */
    public double speedAt(double seconds) {
        if (seconds <= delay || seconds >= delay + duration) return 0;
        double t = (seconds - delay) / duration;
        return (endY - startY) * exponent * Math.pow(t, exponent - 1) / duration;
    }
}
