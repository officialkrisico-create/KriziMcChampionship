package nl.kmc.core.api;

import nl.kmc.core.domain.AchievementDefinition;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Public API for achievement queries and admin operations.
 * Game plugins should fire events rather than calling grant directly.
 * Direct grant is for admin commands and special manual achievements.
 */
public interface AchievementApi {

    /**
     * Grants an achievement to a player immediately.
     * No-op if already unlocked. Thread-safe.
     */
    void grant(UUID uuid, String achievementId);

    /**
     * Revokes a previously granted achievement (admin only).
     * Progress is NOT reset — only the unlock record is removed.
     */
    void revoke(UUID uuid, String achievementId);

    /** Returns true if the player has unlocked this achievement. */
    boolean has(UUID uuid, String achievementId);

    /** Returns all achievement IDs unlocked by this player. */
    Set<String> getUnlocked(UUID uuid);

    /**
     * Returns current progress value for a progress-based achievement.
     * Returns 0 for one-shot achievements or unknown players.
     */
    int getProgress(UUID uuid, String achievementId);

    /** Returns all registered achievement definitions. */
    Collection<AchievementDefinition> getAll();

    /** Returns a specific achievement definition, or null if not found. */
    AchievementDefinition get(String achievementId);

    /**
     * Reloads achievement definitions from YAML files.
     * Existing unlock records are unaffected.
     */
    void reload();
}
