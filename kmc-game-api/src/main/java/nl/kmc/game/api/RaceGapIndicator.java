package nl.kmc.game.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Live race-position line for checkpoint-based race games (Elytra Endrium,
 * Parkour Warrior). Ranks racers by checkpoints/stages reached, then by how
 * close they are to the next one, and tells each viewer where they stand
 * relative to the leader.
 */
public final class RaceGapIndicator {

    private RaceGapIndicator() {}

    /**
     * @param progress   checkpoints (or stages) reached so far
     * @param distToNext distance in blocks to the next checkpoint (0 if unknown/finished)
     */
    public record Racer(UUID id, int progress, double distToNext, boolean finished) {}

    /** One scoreboard line for {@code viewer}, or null if they aren't racing or have already finished. */
    public static String lineFor(UUID viewer, List<Racer> racers) {
        List<Racer> order = new ArrayList<>(racers);
        order.sort(Comparator
                .comparing(Racer::finished).reversed()
                .thenComparing(Comparator.comparingInt(Racer::progress).reversed())
                .thenComparingDouble(Racer::distToNext));

        int rank = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id().equals(viewer)) { rank = i; break; }
        }
        if (rank < 0 || order.get(rank).finished()) return null;
        if (rank == 0) return "§6👑 §eJe leidt!";

        Racer me = order.get(rank), leader = order.get(0);
        String gap;
        if (leader.finished()) {
            gap = "§7finish gehaald door #1";
        } else if (leader.progress() > me.progress()) {
            gap = "§c-" + (leader.progress() - me.progress()) + " cp";
        } else {
            gap = "§c-" + Math.max(1, Math.round(me.distToNext() - leader.distToNext())) + "m";
        }
        return "§7#" + (rank + 1) + "§8/§7" + order.size() + " §8| " + gap;
    }
}
