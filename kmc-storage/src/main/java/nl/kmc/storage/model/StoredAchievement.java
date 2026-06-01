package nl.kmc.storage.model;

import java.time.Instant;
import java.util.UUID;

public final class StoredAchievement {

    public UUID playerUuid;
    public String achievementId;
    public Instant unlockedAt;
    public int eventNumber;

    public StoredAchievement() {}

    public StoredAchievement(UUID playerUuid, String achievementId, int eventNumber) {
        this.playerUuid = playerUuid;
        this.achievementId = achievementId;
        this.eventNumber = eventNumber;
        this.unlockedAt = Instant.now();
    }
}
