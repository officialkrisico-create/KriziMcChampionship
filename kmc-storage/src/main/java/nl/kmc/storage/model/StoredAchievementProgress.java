package nl.kmc.storage.model;

import java.util.UUID;

public final class StoredAchievementProgress {

    public UUID playerUuid;
    public String achievementId;
    public int progress;
    public int target;

    public StoredAchievementProgress() {}

    public StoredAchievementProgress(UUID playerUuid, String achievementId, int progress, int target) {
        this.playerUuid = playerUuid;
        this.achievementId = achievementId;
        this.progress = progress;
        this.target = target;
    }
}
