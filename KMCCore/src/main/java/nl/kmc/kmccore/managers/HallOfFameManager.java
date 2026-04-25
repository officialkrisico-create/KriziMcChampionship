package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.HoFRecord;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Hall of Fame: persistent records that survive all soft + hard resets.
 *
 * <p><b>Architecture:</b> records are evaluated at end-of-event for
 * "single event max" categories (most kills in a game, most points in
 * an event), and continuously tracked for "lifetime total" categories
 * (most kills all time, most points all time, most championship wins).
 *
 * <p><b>Built-in categories:</b>
 * <ul>
 *   <li>{@code most_kills_game}     — most kills in a single game</li>
 *   <li>{@code most_kills_event}    — most kills in a single event</li>
 *   <li>{@code most_kills_lifetime} — most kills total across all events</li>
 *   <li>{@code most_points_event}   — highest event score</li>
 *   <li>{@code most_points_lifetime}— highest lifetime points</li>
 *   <li>{@code most_wins}           — most championship wins (best player of an event)</li>
 * </ul>
 *
 * <p><b>Adding more categories:</b> register them in the constructor.
 * Each category has a strategy (lifetime-counter / per-event-max / per-game-max)
 * and a friendly display name.
 */
public class HallOfFameManager {

    /** Strategies for how a category's value is tracked. */
    public enum Strategy {
        /** Highest value seen in a single game. Updated by recordGameStat(). */
        PER_GAME_MAX,
        /** Highest value seen in a single tournament. Evaluated at event end. */
        PER_EVENT_MAX,
        /** Cumulative across the player's whole history. Updated continuously. */
        LIFETIME_TOTAL
    }

    /** Definition of a single HoF category. */
    public static class Category {
        public final String   id;
        public final String   displayName;
        public final Strategy strategy;
        public Category(String id, String name, Strategy strategy) {
            this.id          = id;
            this.displayName = name;
            this.strategy    = strategy;
        }
    }

    private final KMCCore plugin;
    private final Map<String, Category>  categories = new LinkedHashMap<>();
    private final Map<String, HoFRecord> records    = new LinkedHashMap<>();

    /** Per-game stat counters. Cleared at game end via clearGameStats(). */
    private final Map<UUID, Integer> killsThisGame  = new HashMap<>();

    /** Per-event stat counters. Cleared at event end via clearEventStats(). */
    private final Map<UUID, Integer> killsThisEvent = new HashMap<>();

    public HallOfFameManager(KMCCore plugin) {
        this.plugin = plugin;
        registerDefaults();
        loadFromDb();
    }

    // ----------------------------------------------------------------
    // Setup
    // ----------------------------------------------------------------

    private void registerDefaults() {
        registerCategory("most_kills_game",     "Meeste Kills (1 Game)",     Strategy.PER_GAME_MAX);
        registerCategory("most_kills_event",    "Meeste Kills (1 Event)",    Strategy.PER_EVENT_MAX);
        registerCategory("most_kills_lifetime", "Meeste Kills (Allertijden)",Strategy.LIFETIME_TOTAL);
        registerCategory("most_points_event",   "Meeste Punten (1 Event)",   Strategy.PER_EVENT_MAX);
        registerCategory("most_points_lifetime","Meeste Punten (Allertijden)",Strategy.LIFETIME_TOTAL);
        registerCategory("most_wins",           "Meeste Championship Wins",  Strategy.LIFETIME_TOTAL);
    }

    public void registerCategory(String id, String displayName, Strategy strategy) {
        categories.put(id, new Category(id, displayName, strategy));
    }

    private void loadFromDb() {
        records.clear();
        records.putAll(plugin.getDatabaseManager().loadAllHoFRecords());
        plugin.getLogger().info("Loaded " + records.size() + " HoF records.");
    }

    // ----------------------------------------------------------------
    // Tracking — called by listeners and managers
    // ----------------------------------------------------------------

    /**
     * Increment a kill for the killer. Called by PlayerKillListener.
     * Updates per-game / per-event counters and immediately checks the
     * "lifetime kills" record for new highs.
     */
    public void recordKill(Player killer) {
        if (killer == null) return;
        UUID uuid = killer.getUniqueId();
        killsThisGame.merge(uuid, 1, Integer::sum);
        killsThisEvent.merge(uuid, 1, Integer::sum);

        // Lifetime kills update — read from PlayerData (already incremented by PointsManager.awardKill)
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd != null) {
            tryUpdate("most_kills_lifetime", uuid, killer.getName(), pd.getKills());
        }
    }

    /**
     * Lifetime points update. Called every time a player earns points.
     * Reads the player's lifetime-running total and updates the record
     * if it's a new high.
     *
     * <p>NOTE: "lifetime" here means "since the database was last hard-reset".
     * Hard-reset wipes player rows but keeps HoF records — meaning lifetime
     * counters effectively start at 0 again post-hardreset, BUT the record
     * holders from previous events stay enshrined. This is intentional.
     */
    public void onPointsAwarded(UUID uuid, String name, int newTotal) {
        if (uuid == null || name == null) return;
        tryUpdate("most_points_lifetime", uuid, name, newTotal);
    }

    /** Snapshot kills counters at game end. */
    public void evaluateGameEnd() {
        for (var entry : killsThisGame.entrySet()) {
            UUID uuid = entry.getKey();
            int kills = entry.getValue();
            String name = lookupName(uuid);
            tryUpdate("most_kills_game", uuid, name, kills);
        }
        killsThisGame.clear();
    }

    /** Snapshot per-event counters at tournament end. */
    public void evaluateEventEnd() {
        // Per-event kills
        for (var entry : killsThisEvent.entrySet()) {
            UUID uuid = entry.getKey();
            int kills = entry.getValue();
            String name = lookupName(uuid);
            tryUpdate("most_kills_event", uuid, name, kills);
        }

        // Per-event points — read from current player data, before reset
        for (PlayerData pd : plugin.getPlayerDataManager().getLeaderboard()) {
            tryUpdate("most_points_event", pd.getUuid(), pd.getName(), pd.getPoints());
        }

        // Best player of this event gets a championship win
        plugin.getPlayerDataManager().getLeaderboard().stream().findFirst().ifPresent(best -> {
            HoFRecord cur = records.get("most_wins");
            // Increment that player's "wins" counter — read from PlayerData
            // (PlayerData.wins is per-event so we need to track championships separately)
            String winsKey = "championship_wins:" + best.getUuid();
            int currentWins = parseIntOr(plugin.getDatabaseManager().getTournamentValue(winsKey, "0"));
            currentWins++;
            plugin.getDatabaseManager().setTournamentValue(winsKey, String.valueOf(currentWins));
            tryUpdate("most_wins", best.getUuid(), best.getName(), currentWins);
        });

        killsThisEvent.clear();
    }

    /** Reset just per-event tracking (called from /kmctournament reset). */
    public void clearEventStats() {
        killsThisEvent.clear();
        killsThisGame.clear();
    }

    private int parseIntOr(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private String lookupName(UUID uuid) {
        Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd != null) return pd.getName();
        var off = org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
        return off != null ? off : uuid.toString();
    }

    // ----------------------------------------------------------------
    // Record update logic
    // ----------------------------------------------------------------

    /**
     * Beats existing record? Save it. Otherwise no-op.
     * Returns true if the record was updated.
     */
    public boolean tryUpdate(String categoryId, UUID uuid, String name, long value) {
        Category cat = categories.get(categoryId);
        if (cat == null) return false;
        if (uuid == null || name == null) return false;

        HoFRecord existing = records.get(categoryId);
        boolean isNewRecord = existing == null || value > existing.getValue();

        if (!isNewRecord) return false;

        int eventNumber = plugin.getTournamentManager().getEventNumber();
        if (existing == null) {
            HoFRecord rec = new HoFRecord(categoryId, uuid, name, value, eventNumber,
                    System.currentTimeMillis());
            records.put(categoryId, rec);
            plugin.getDatabaseManager().saveHoFRecord(rec);
        } else {
            existing.update(uuid, name, value, eventNumber);
            plugin.getDatabaseManager().saveHoFRecord(existing);
        }

        plugin.getLogger().info("[HoF] New record: " + cat.displayName + " — "
                + name + " (" + value + ")");

        return true;
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public Collection<Category> getCategories() {
        return Collections.unmodifiableCollection(categories.values());
    }

    public Category getCategory(String id) { return categories.get(id); }

    public HoFRecord getRecord(String categoryId) { return records.get(categoryId); }

    public Map<String, HoFRecord> getAllRecords() {
        return Collections.unmodifiableMap(records);
    }

    // ----------------------------------------------------------------
    // Admin
    // ----------------------------------------------------------------

    public void clearCategory(String categoryId) {
        plugin.getDatabaseManager().clearHoFRecord(categoryId);
        records.remove(categoryId);
    }

    public void clearAll() {
        plugin.getDatabaseManager().clearAllHoFRecords();
        records.clear();
        // Also clear the championship_wins:* keys
        // (we don't have a wildcard delete API; we just leave them — they're harmless)
    }

    public void reload() {
        loadFromDb();
    }
}
