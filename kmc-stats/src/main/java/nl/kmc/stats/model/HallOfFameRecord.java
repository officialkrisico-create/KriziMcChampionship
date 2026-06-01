package nl.kmc.stats.model;

import java.time.Instant;
import java.util.UUID;

public final class HallOfFameRecord {

    public enum Category {
        MOST_WINS, MOST_KILLS, MOST_MVPS, MOST_CHAMPIONSHIPS,
        HIGHEST_WIN_RATE, BEST_PLACEMENT_AVG, LONGEST_STREAK, MOST_CLUTCHES
    }

    public Category category;
    public UUID     playerUuid;
    public String   playerName;
    public double   value;
    public int      eventNumber;
    public Instant  recordedAt;

    public HallOfFameRecord() { this.recordedAt = Instant.now(); }

    public HallOfFameRecord(Category category, UUID playerUuid, String playerName,
                            double value, int eventNumber) {
        this.category    = category;
        this.playerUuid  = playerUuid;
        this.playerName  = playerName;
        this.value       = value;
        this.eventNumber = eventNumber;
        this.recordedAt  = Instant.now();
    }
}
