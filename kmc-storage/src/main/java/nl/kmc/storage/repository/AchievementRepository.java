package nl.kmc.storage.repository;

import nl.kmc.storage.model.StoredAchievement;
import nl.kmc.storage.model.StoredAchievementProgress;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AchievementRepository {

    CompletableFuture<List<StoredAchievement>> findUnlockedByPlayer(UUID uuid);

    CompletableFuture<Void> recordUnlock(StoredAchievement achievement);

    CompletableFuture<Optional<StoredAchievementProgress>> findProgress(UUID uuid, String achievementId);

    CompletableFuture<List<StoredAchievementProgress>> findAllProgress(UUID uuid);

    CompletableFuture<Void> saveProgress(StoredAchievementProgress progress);
}
