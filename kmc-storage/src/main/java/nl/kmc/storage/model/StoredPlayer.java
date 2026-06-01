package nl.kmc.storage.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Flat persistence record for a player — no Bukkit dependencies. */
public final class StoredPlayer {

    public UUID uuid;
    public String name;
    public String teamId;          // null when unassigned

    // Tournament-scoped (reset on soft reset)
    public int points;
    public int kills;
    public int deaths;
    public int wins;
    public int gamesPlayed;

    // Lifetime (only cleared on hard reset)
    public long playTimeMinutes;
    public int winStreak;
    public int bestWinStreak;
    public Map<String, Integer> winsPerGame = new HashMap<>(); // gameId → wins

    public StoredPlayer() {}

    public StoredPlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }
}
