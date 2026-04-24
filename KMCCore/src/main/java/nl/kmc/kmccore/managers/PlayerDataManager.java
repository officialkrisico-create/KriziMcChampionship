package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory cache and persistence layer for {@link PlayerData}.
 *
 * <p>Player data is loaded from the database on first access (usually
 * on player join) and saved back on logout and plugin shutdown.
 */
public class PlayerDataManager {

    private final KMCCore plugin;

    /** Runtime cache: UUID → PlayerData. */
    private final Map<UUID, PlayerData> cache = new HashMap<>();

    public PlayerDataManager(KMCCore plugin) {
        this.plugin = plugin;
    }

    // ----------------------------------------------------------------
    // Access
    // ----------------------------------------------------------------

    /**
     * Returns cached data, or loads from DB if not cached.
     *
     * @return never {@code null} – creates a new record if necessary
     */
    public PlayerData getOrCreate(UUID uuid, String name) {
        if (cache.containsKey(uuid)) {
            PlayerData pd = cache.get(uuid);
            if (name != null) pd.setName(name);  // keep name up-to-date
            return pd;
        }

        PlayerData pd = plugin.getDatabaseManager().loadPlayer(uuid);
        if (pd == null) {
            pd = new PlayerData(uuid, name != null ? name : uuid.toString());
        } else if (name != null) {
            pd.setName(name);
        }

        cache.put(uuid, pd);
        return pd;
    }

    /**
     * Returns cached data, or {@code null} if not loaded.
     * Use {@link #getOrCreate} for guaranteed non-null access.
     */
    public PlayerData get(UUID uuid) {
        return cache.get(uuid);
    }

    /** Removes a player from the cache (usually on logout). */
    public void unload(UUID uuid) {
        PlayerData pd = cache.remove(uuid);
        if (pd != null) plugin.getDatabaseManager().savePlayer(pd);
    }

    // ----------------------------------------------------------------
    // Leaderboard
    // ----------------------------------------------------------------

    /**
     * Returns all player data sorted by points descending.
     * Merges cached data with DB data to ensure completeness.
     */
    public List<PlayerData> getLeaderboard() {
        // Load everything from DB, then overlay cache
        List<PlayerData> all = plugin.getDatabaseManager().loadAllPlayers();
        Map<UUID, PlayerData> merged = new LinkedHashMap<>();
        for (PlayerData pd : all) merged.put(pd.getUuid(), pd);

        // Cached data (most recent) wins
        for (PlayerData pd : cache.values()) merged.put(pd.getUuid(), pd);

        return merged.values().stream()
                .sorted(Comparator.comparingInt(PlayerData::getPoints).reversed())
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    // Bulk operations
    // ----------------------------------------------------------------

    /** Saves all cached players to the database. */
    public void saveAll() {
        for (PlayerData pd : cache.values()) {
            plugin.getDatabaseManager().savePlayer(pd);
        }
    }

    /**
     * Soft reset — clears tournament stats (points/kills/wins/streak) but
     * keeps lifetime data (bestWinStreak, winsPerGame, playtime).
     * Used at natural tournament end so a new event starts fresh.
     */
    public void resetSeasonStats() {
        for (PlayerData pd : cache.values()) {
            pd.setPoints(0);
            pd.setKills(0);
            pd.setWins(0);
            pd.setWinStreak(0);
            pd.setGamesPlayed(0);
        }
        saveAll();
    }

    /** Hard reset — wipes everything including lifetime stats. */
    public void resetAll() {
        for (PlayerData pd : cache.values()) {
            pd.setPoints(0);
            pd.setKills(0);
            pd.setWins(0);
            pd.setWinStreak(0);
            pd.setBestWinStreak(0);
            pd.setGamesPlayed(0);
            pd.setTotalPlayTimeMinutes(0);
            pd.setWinsPerGame(new java.util.HashMap<>());
        }
        saveAll();
        plugin.getDatabaseManager().resetAll(true);
    }
}
