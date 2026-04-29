package nl.kmc.kmccore.achievements;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Tracks which achievements each player has earned, evaluates conditions
 * when game events happen, and persists unlocks to the database.
 *
 * <p>Hooks into the game lifecycle:
 * <ul>
 *   <li>{@link #onKill(UUID, int)} — called when a player gets a kill</li>
 *   <li>{@link #onGameWin(UUID, String, long, boolean)} — when they finish 1st</li>
 *   <li>{@link #onGameEnd(UUID, String, int, boolean)} — when any game ends for them</li>
 *   <li>{@link #onTournamentEnd(UUID, int, boolean)} — when a tournament finishes</li>
 *   <li>{@link #onTeamChat(UUID)}, {@link #onReadyCommand(UUID)}, etc. — misc counters</li>
 * </ul>
 *
 * <p>Cumulative counters (regular, veteran, kills) are stored as columns
 * on the achievement_progress table, NOT on PlayerData (keeps the model
 * clean).
 */
public class AchievementManager {

    private final KMCCore plugin;

    /** Per-player set of unlocked achievement IDs. */
    private final Map<UUID, Set<String>>  unlocked = new HashMap<>();

    /** Per-player progress counters for incremental achievements. */
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();

    /** Per-player win streak tracker (for hat_trick). */
    private final Map<UUID, Integer> currentWinStreak = new HashMap<>();

    /** Tournament-scoped counters (reset on tournament start). */
    private final Map<UUID, Integer> tournamentPoints = new HashMap<>();

    public AchievementManager(KMCCore plugin) {
        this.plugin = plugin;
        ensureTables();
        loadAll();
    }

    // ----------------------------------------------------------------
    // Persistence
    // ----------------------------------------------------------------

    private void ensureTables() {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_achievements (
                    uuid VARCHAR(36) NOT NULL,
                    achievement_id VARCHAR(64) NOT NULL,
                    unlocked_at BIGINT NOT NULL,
                    PRIMARY KEY (uuid, achievement_id)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS achievement_progress (
                    uuid VARCHAR(36) NOT NULL,
                    achievement_id VARCHAR(64) NOT NULL,
                    progress INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, achievement_id)
                )""");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create achievement tables", e);
        }
    }

    private void loadAll() {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, achievement_id FROM player_achievements")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String aid = rs.getString("achievement_id");
                    unlocked.computeIfAbsent(uuid, k -> new HashSet<>()).add(aid);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player_achievements", e);
        }

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, achievement_id, progress FROM achievement_progress")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String aid = rs.getString("achievement_id");
                    int prog = rs.getInt("progress");
                    progress.computeIfAbsent(uuid, k -> new HashMap<>()).put(aid, prog);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load achievement_progress", e);
        }

        plugin.getLogger().info("Loaded achievements for " + unlocked.size() + " player(s).");
    }

    private void persistUnlock(UUID uuid, String aid, long ts) {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO player_achievements (uuid, achievement_id, unlocked_at) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, aid);
            ps.setLong(3, ts);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist achievement unlock", e);
        }
    }

    private void persistProgress(UUID uuid, String aid, int newValue) {
        Connection c = plugin.getDatabaseManager().getConnection();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO achievement_progress (uuid, achievement_id, progress) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, aid);
            ps.setInt(3, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist achievement progress", e);
        }
    }

    // ----------------------------------------------------------------
    // Public state queries
    // ----------------------------------------------------------------

    public boolean hasUnlocked(UUID uuid, String aid) {
        Set<String> set = unlocked.get(uuid);
        return set != null && set.contains(aid);
    }

    public Set<String> getUnlocked(UUID uuid) {
        return unlocked.getOrDefault(uuid, Collections.emptySet());
    }

    public int getProgress(UUID uuid, String aid) {
        return progress.getOrDefault(uuid, Collections.emptyMap()).getOrDefault(aid, 0);
    }

    public int unlockedCount(UUID uuid) {
        return getUnlocked(uuid).size();
    }

    // ----------------------------------------------------------------
    // Unlock mechanism
    // ----------------------------------------------------------------

    /**
     * Forcibly unlock an achievement for a player (no-op if already unlocked).
     * Persists, broadcasts (per rarity), and plays the unlock sound.
     */
    public void unlock(UUID uuid, String aid) {
        Achievement a = AchievementRegistry.get(aid);
        if (a == null) return;
        if (hasUnlocked(uuid, aid)) return;

        unlocked.computeIfAbsent(uuid, k -> new HashSet<>()).add(aid);
        persistUnlock(uuid, aid, System.currentTimeMillis());

        Player p = Bukkit.getPlayer(uuid);
        notifyUnlock(p, a);
    }

    /** Increment progress on a counter achievement; auto-unlocks when threshold hit. */
    public void incrementProgress(UUID uuid, String aid) {
        incrementProgress(uuid, aid, 1);
    }

    public void incrementProgress(UUID uuid, String aid, int delta) {
        Achievement a = AchievementRegistry.get(aid);
        if (a == null || !a.isProgressBased()) return;
        if (hasUnlocked(uuid, aid)) return;

        int current = getProgress(uuid, aid) + delta;
        progress.computeIfAbsent(uuid, k -> new HashMap<>()).put(aid, current);
        persistProgress(uuid, aid, current);

        if (current >= a.getProgressThreshold()) {
            unlock(uuid, aid);
        }
    }

    private void notifyUnlock(Player p, Achievement a) {
        if (a.getRarity().broadcastOnUnlock()) {
            // Public broadcast — rare/legendary
            String prefix = a.getRarity() == Achievement.Rarity.LEGENDARY
                    ? ChatColor.GOLD + "" + ChatColor.BOLD + "★ LEGENDARY"
                    : ChatColor.AQUA + "" + ChatColor.BOLD + "✦ RARE";
            String name = (p != null ? p.getName() : "Een speler");
            Bukkit.broadcastMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "ACHIEVEMENT! "
                    + ChatColor.GRAY + name + " heeft "
                    + a.getRarity().getChatColor() + a.getName()
                    + ChatColor.GRAY + " behaald — "
                    + ChatColor.WHITE + a.getDescription() + " " + prefix);

            // Big satisfying sound for everyone
            for (Player listener : Bukkit.getOnlinePlayers()) {
                listener.playSound(listener.getLocation(),
                        a.getRarity() == Achievement.Rarity.LEGENDARY
                                ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                                : Sound.ENTITY_PLAYER_LEVELUP,
                        0.6f, 1.2f);
            }
        }

        // Private title for the earner
        if (p != null) {
            p.sendTitle(
                    a.getRarity().getChatColor() + "" + ChatColor.BOLD + "🏆 " + a.getName(),
                    ChatColor.GRAY + a.getDescription(),
                    10, 80, 30);
            p.playSound(p.getLocation(),
                    a.getRarity() == Achievement.Rarity.LEGENDARY
                            ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                            : Sound.ENTITY_PLAYER_LEVELUP,
                    1.0f, 1.5f);
        }

        plugin.getLogger().info("Achievement unlocked: " + a.getId()
                + " by " + (p != null ? p.getName() : "unknown"));
    }

    // ----------------------------------------------------------------
    // Event hooks (called by listeners / minigames)
    // ----------------------------------------------------------------

    /** Called on every kill the player makes. */
    public void onKill(UUID uuid, int killsThisGame) {
        unlock(uuid, "first_blood");
        if (killsThisGame >= 3) unlock(uuid, "knockout");
        if (killsThisGame >= 5) unlock(uuid, "pentakill");
    }

    /**
     * Called when a player wins (finishes 1st in) a single game.
     * @param gameId      game ID
     * @param finishTimeMs how long the run took (for speedrunner check)
     * @param noDeaths    true if the player didn't die during this game
     */
    public void onGameWin(UUID uuid, String gameId, long finishTimeMs, boolean noDeaths) {
        unlock(uuid, "first_win");

        // Win streak tracker
        int streak = currentWinStreak.getOrDefault(uuid, 0) + 1;
        currentWinStreak.put(uuid, streak);
        if (streak >= 3) unlock(uuid, "hat_trick");

        // Speedrunner — AE under 60s
        if (gameId.equalsIgnoreCase("adventure_escape") && finishTimeMs > 0
                && finishTimeMs < 60_000L) {
            unlock(uuid, "speedrunner");
        }

        // Untouchable — no deaths in this game
        if (noDeaths) unlock(uuid, "untouchable");

        // Decathlete progress — track distinct game wins
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd != null) {
            int distinctGames = pd.getWinsPerGame() != null ? pd.getWinsPerGame().size() : 0;
            if (distinctGames >= 12) unlock(uuid, "decathlete");
        }
    }

    /** Called when a game ends (regardless of placement). */
    public void onGameEnd(UUID uuid, String gameId, int placement, boolean isFinishGame) {
        if (placement != 1) currentWinStreak.put(uuid, 0); // streak broken
        if (isFinishGame && placement >= 1) unlock(uuid, "first_finish");
        incrementProgress(uuid, "participant");
        incrementProgress(uuid, "regular");
        incrementProgress(uuid, "veteran");

        // Legend check
        PlayerData pd = plugin.getPlayerDataManager().get(uuid);
        if (pd != null && pd.getGamesPlayed() >= 500 && pd.getWins() >= 50) {
            unlock(uuid, "legend");
        }
    }

    /**
     * Called when the tournament ends.
     * @param finalRank  player's rank in the tournament (1 = MVP)
     * @param teamWon    true if their team finished #1
     */
    public void onTournamentEnd(UUID uuid, int finalRank, boolean teamWon) {
        if (finalRank == 1) unlock(uuid, "tournament_mvp");
        if (teamWon)        unlock(uuid, "team_champion");

        // Reset tournament-scoped state
        currentWinStreak.remove(uuid);
        tournamentPoints.remove(uuid);
    }

    public void onTournamentStart() {
        currentWinStreak.clear();
        tournamentPoints.clear();
    }

    /** Called whenever a player gains points — tracks tournament total. */
    public void onPointsGained(UUID uuid, int amount) {
        int total = tournamentPoints.getOrDefault(uuid, 0) + amount;
        tournamentPoints.put(uuid, total);
        if (total >= 100)  unlock(uuid, "century");
        if (total >= 500)  unlock(uuid, "high_scorer");
    }

    /** Hooks for misc unlocks. */
    public void onTeamJoin(UUID uuid)   { unlock(uuid, "team_player"); }
    public void onVote(UUID uuid)       { unlock(uuid, "voter"); }
    public void onPrefsEdit(UUID uuid)  { unlock(uuid, "stylist"); }
    public void onReadyCommand(UUID uuid)   { incrementProgress(uuid, "ready"); }
    public void onTeamChat(UUID uuid)       { incrementProgress(uuid, "social"); }
}
