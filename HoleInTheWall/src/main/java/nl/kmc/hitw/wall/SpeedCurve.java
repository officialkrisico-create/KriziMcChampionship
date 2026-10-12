package nl.kmc.hitw.wall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Wall speed (blocks/second) as a function of round time, linearly interpolated between config keyframes. */
public final class SpeedCurve {

    public record Key(double seconds, double speed) {}

    private final List<Key> keys = new ArrayList<>();

    public SpeedCurve(List<Key> keys) {
        this.keys.addAll(keys);
        this.keys.sort(Comparator.comparingDouble(Key::seconds));
        if (this.keys.isEmpty()) this.keys.add(new Key(0, 6));
    }

    public double speedAt(double seconds) {
        if (seconds <= keys.get(0).seconds()) return keys.get(0).speed();
        for (int i = 1; i < keys.size(); i++) {
            Key a = keys.get(i - 1), b = keys.get(i);
            if (seconds <= b.seconds()) {
                double span = b.seconds() - a.seconds();
                double f = span <= 0 ? 1 : (seconds - a.seconds()) / span;
                return a.speed() + (b.speed() - a.speed()) * f;
            }
        }
        return keys.get(keys.size() - 1).speed();
    }
}
