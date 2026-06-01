package nl.kmc.stats.clutch;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.stats.model.GameStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Detects kill streaks — N kills without dying. */
public final class KillStreakDetector implements ClutchDetector {

    private final int threshold;
    private final Map<UUID, Integer> lastReportedStreak = new HashMap<>();
    private final Map<UUID, Integer> streakAtLastDeath  = new HashMap<>();

    public KillStreakDetector(int threshold) { this.threshold = threshold; }

    @Override
    public Optional<ClutchMomentEvent> evaluate(UUID uuid, String name, String gameId,
                                                GameStats stats, Map<UUID, GameStats> all, Context ctx) {
        // Calculate current streak: kills since last death
        int deaths = stats.deaths;
        int kills  = stats.kills;

        // Track per-player death baseline to compute streak
        int deathBaseline = streakAtLastDeath.getOrDefault(uuid, 0);
        if (deaths > deathBaseline) {
            streakAtLastDeath.put(uuid, deaths);
            lastReportedStreak.remove(uuid);
        }

        int streak = kills - deathBaseline;
        int lastReported = lastReportedStreak.getOrDefault(uuid, 0);

        if (streak < threshold || streak <= lastReported) return Optional.empty();

        lastReportedStreak.put(uuid, streak);

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return Optional.empty();

        String desc = streak + " kill streak!";
        return Optional.of(new ClutchMomentEvent(
                player, ClutchMomentEvent.ClutchType.KILL_STREAK, desc, gameId));
    }

    public void reset() {
        lastReportedStreak.clear();
        streakAtLastDeath.clear();
    }
}
