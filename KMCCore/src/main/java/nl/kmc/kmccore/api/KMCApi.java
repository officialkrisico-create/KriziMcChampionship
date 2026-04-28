package nl.kmc.kmccore.api;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Public API surface for KMC Core. Comprehensive version with all
 * methods the various minigames + managers expect.
 *
 * <p>This is purely additive vs. older versions — every method that
 * existed in any previous patch is present here. Replacing your
 * existing KMCApi.java with this one is safe and won't break anything.
 *
 * <p>Provides:
 * <ul>
 *   <li>Team / player data queries</li>
 *   <li>Point & placement awards (routed through PointsManager)</li>
 *   <li>Scoreboard ownership lock (acquire/release/isOwned)</li>
 *   <li>Game lifecycle hooks (onGameStart, onGameEnd, onRoundStart,
 *       onTournamentStart, onTournamentEnd) + their fire methods</li>
 *   <li>Per-player tournament stats helper (recordGameParticipation)</li>
 * </ul>
 */
public class KMCApi {

    private final KMCCore plugin;

    // ---- Hook lists ------------------------------------------------
    private final List<BiConsumer<String, String>> gameEndHooks         = new ArrayList<>();
    private final List<Consumer<String>>           gameStartHooks       = new ArrayList<>();
    private final List<Consumer<Integer>>          roundStartHooks      = new ArrayList<>();
    private final List<Runnable>                   tournamentStartHooks = new ArrayList<>();
    private final List<Runnable>                   tournamentEndHooks   = new ArrayList<>();

    /** Name of the minigame currently owning the scoreboard, or null. */
    private volatile String scoreboardOwner;

    public KMCApi(KMCCore plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public KMCTeam getTeamByPlayer(UUID uuid) { return plugin.getTeamManager().getTeamByPlayer(uuid); }
    public KMCTeam getTeam(String teamId)     { return plugin.getTeamManager().getTeam(teamId); }
    public List<KMCTeam> getTeamLeaderboard() { return plugin.getTeamManager().getTeamsSortedByPoints(); }
    public PlayerData getPlayerData(UUID uuid)                   { return plugin.getPlayerDataManager().get(uuid); }
    public List<PlayerData> getPlayerLeaderboard()               { return plugin.getPlayerDataManager().getLeaderboard(); }

    public String getActiveGameName() {
        return plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getDisplayName() : null;
    }
    public String getActiveGameId() {
        return plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getId() : null;
    }

    public double  getCurrentMultiplier() { return plugin.getTournamentManager().getMultiplier(); }
    public int     getCurrentRound()      { return plugin.getTournamentManager().getCurrentRound(); }
    public boolean isTournamentActive()   { return plugin.getTournamentManager().isActive(); }

    // ----------------------------------------------------------------
    // Point & placement mutations (route through PointsManager)
    // ----------------------------------------------------------------

    public int givePoints(UUID uuid, int amount) {
        return plugin.getPointsManager().awardPlayerPoints(uuid, amount);
    }
    public int giveTeamPoints(String teamId, int amount) {
        plugin.getPointsManager().addTeamPoints(teamId, amount);
        return amount;
    }
    public int awardPlayerPlacement(UUID uuid, int position) {
        return plugin.getPointsManager().awardPlayerPlacement(uuid, position);
    }
    public int awardTeamPlacement(String teamId, int position) {
        return plugin.getPointsManager().awardTeamPlacement(teamId, position);
    }

    /**
     * Legacy no-op kept for compile compatibility. Coins were removed
     * from the system; calls to this method simply do nothing.
     * Will be deleted in a future release once all callers are cleaned up.
     */
    @Deprecated
    public void giveCoins(UUID uuid, int amount) {
        // no-op — coins removed from system
    }

    // ----------------------------------------------------------------
    // Scoreboard ownership lock
    // ----------------------------------------------------------------

    /**
     * Claims the scoreboard for a minigame. While owned, KMCCore's lobby
     * scoreboard manager will NOT overwrite player scoreboards.
     */
    public boolean acquireScoreboard(String minigameName) {
        if (minigameName == null) return false;
        if (scoreboardOwner != null && !scoreboardOwner.equals(minigameName)) {
            plugin.getLogger().warning("Scoreboard already owned by '" + scoreboardOwner
                    + "' — '" + minigameName + "' was denied.");
            return false;
        }
        scoreboardOwner = minigameName;
        plugin.getLogger().info("Scoreboard acquired by " + minigameName);
        return true;
    }

    /** Releases scoreboard ownership. Only the current owner can release. */
    public void releaseScoreboard(String minigameName) {
        if (minigameName == null) return;
        if (!minigameName.equals(scoreboardOwner)) {
            plugin.getLogger().warning("Release called by '" + minigameName
                    + "' but owner is '" + scoreboardOwner + "' — ignored.");
            return;
        }
        scoreboardOwner = null;
        plugin.getLogger().info("Scoreboard released by " + minigameName);

        // Force a fresh repaint of the lobby sidebar / tab list now
        try { plugin.getScoreboardManager().refreshAll(); } catch (Exception ignored) {}
        try { plugin.getTabListManager().refreshAll();    } catch (Exception ignored) {}
    }

    public boolean isScoreboardOwnedByMinigame() { return scoreboardOwner != null; }
    public String  getScoreboardOwner()          { return scoreboardOwner; }

    // ----------------------------------------------------------------
    // Per-player tournament stats helper
    // ----------------------------------------------------------------

    /**
     * Records standard end-of-game player stats: increments games played,
     * adds a win (or resets streak) based on whether the player won.
     *
     * <p>Call this from each minigame's end-of-game routine for every
     * participant.
     */
    public void recordGameParticipation(UUID uuid, String name, String gameId, boolean won) {
        var pd = plugin.getPlayerDataManager().getOrCreate(uuid, name);
        if (pd == null) return;
        pd.incrementGamesPlayed();
        if (won) pd.addWin(gameId);
        else     pd.resetStreak();
    }

    // ----------------------------------------------------------------
    // Hooks (registration)
    // ----------------------------------------------------------------

    /** Fires when a game starts. Argument: gameId (e.g. "adventure_escape"). */
    public void onGameStart(Consumer<String> hook)              { gameStartHooks.add(hook); }
    public void onGameEnd(BiConsumer<String, String> hook)      { gameEndHooks.add(hook); }
    public void onRoundStart(Consumer<Integer> hook)            { roundStartHooks.add(hook); }
    public void onTournamentStart(Runnable hook)                { tournamentStartHooks.add(hook); }
    public void onTournamentEnd(Runnable hook)                  { tournamentEndHooks.add(hook); }

    // ----------------------------------------------------------------
    // Hooks (firing — internal use by KMCCore managers)
    // ----------------------------------------------------------------

    public void fireGameStart(String gameId) {
        for (var h : gameStartHooks) {
            try { h.accept(gameId); }
            catch (Exception e) { plugin.getLogger().warning("gameStart hook error: " + e.getMessage()); }
        }
    }
    public void fireGameEnd(String gameName, String winner) {
        for (var h : gameEndHooks) {
            try { h.accept(gameName, winner); }
            catch (Exception e) { plugin.getLogger().warning("gameEnd hook error: " + e.getMessage()); }
        }
    }
    public void fireRoundStart(int round) {
        for (var h : roundStartHooks) {
            try { h.accept(round); }
            catch (Exception e) { plugin.getLogger().warning("roundStart hook error: " + e.getMessage()); }
        }
    }
    public void fireTournamentStart() {
        for (var h : tournamentStartHooks) {
            try { h.run(); }
            catch (Exception e) { plugin.getLogger().warning("tournamentStart hook error: " + e.getMessage()); }
        }
    }
    public void fireTournamentEnd() {
        for (var h : tournamentEndHooks) {
            try { h.run(); }
            catch (Exception e) { plugin.getLogger().warning("tournamentEnd hook error: " + e.getMessage()); }
        }
    }
}