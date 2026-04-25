package nl.kmc.kmccore.api;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Public API surface for KMC Core.
 *
 * <p>All point-giving methods route through {@link nl.kmc.kmccore.managers.PointsManager}
 * so the "personal points also count for the team" rule is applied automatically.
 */
public class KMCApi {

    private final KMCCore plugin;

    private final List<BiConsumer<String, String>> gameEndHooks        = new ArrayList<>();
    private final List<Consumer<Integer>>          roundStartHooks     = new ArrayList<>();
    private final List<Runnable>                   tournamentStartHooks = new ArrayList<>();

    /** Game start hooks — fires when KMCCore picks/launches a game. */
    private final List<Consumer<String>>           gameStartHooks      = new ArrayList<>();

    /** Scoreboard ownership — only one minigame can own it at a time. */
    private String scoreboardOwner = null;

    public KMCApi(KMCCore plugin) { this.plugin = plugin; }

    // ---- Queries ---------------------------------------------------

    public KMCTeam getTeamByPlayer(UUID uuid) { return plugin.getTeamManager().getTeamByPlayer(uuid); }
    public KMCTeam getTeam(String teamId)     { return plugin.getTeamManager().getTeam(teamId); }
    public List<KMCTeam> getTeamLeaderboard() { return plugin.getTeamManager().getTeamsSortedByPoints(); }
    public PlayerData getPlayerData(UUID uuid)                   { return plugin.getPlayerDataManager().get(uuid); }
    public List<PlayerData> getPlayerLeaderboard()               { return plugin.getPlayerDataManager().getLeaderboard(); }
    public String getActiveGameName() {
        return plugin.getGameManager().getActiveGame() != null
               ? plugin.getGameManager().getActiveGame().getDisplayName() : null;
    }
    public double  getCurrentMultiplier() { return plugin.getTournamentManager().getMultiplier(); }
    public int     getCurrentRound()      { return plugin.getTournamentManager().getCurrentRound(); }
    public boolean isTournamentActive()   { return plugin.getTournamentManager().isActive(); }

    // ---- Mutations (all route through PointsManager) --------------

    /**
     * Awards points to a player. The same amount is automatically
     * added to the player's team if they have one.
     *
     * @return actual points awarded
     */
    public int givePoints(UUID uuid, int amount) {
        return plugin.getPointsManager().awardPlayerPoints(uuid, amount);
    }

    /**
     * Awards team-only points (does NOT add to any individual player).
     * Use for team-based game placement bonuses.
     */
    public int giveTeamPoints(String teamId, int amount) {
        plugin.getPointsManager().addTeamPoints(teamId, amount);
        return amount;
    }

    /** Placement helper — position 1 = 1st place. Auto-credits team. */
    public int awardPlayerPlacement(UUID uuid, int position) {
        return plugin.getPointsManager().awardPlayerPlacement(uuid, position);
    }

    /** Team-placement helper for team-based games. Independent of player scores. */
    public int awardTeamPlacement(String teamId, int position) {
        return plugin.getPointsManager().awardTeamPlacement(teamId, position);
    }

    // ---- Hooks -----------------------------------------------------

    public void onGameEnd(BiConsumer<String, String> hook)      { gameEndHooks.add(hook); }
    public void onRoundStart(Consumer<Integer> hook)            { roundStartHooks.add(hook); }
    public void onTournamentStart(Runnable hook)                { tournamentStartHooks.add(hook); }

    /**
     * Register a hook to be called when KMCCore launches a game.
     * The hook receives the game id (e.g. "adventure_escape").
     * Used by minigame plugins to auto-start their countdowns.
     */
    public void onGameStart(Consumer<String> hook)              { gameStartHooks.add(hook); }

    public void fireGameEnd(String gameName, String winner) {
        for (var h : gameEndHooks) { try { h.accept(gameName, winner); } catch (Exception ignored) {} }
    }
    public void fireRoundStart(int round) {
        for (var h : roundStartHooks) { try { h.accept(round); } catch (Exception ignored) {} }
    }
    public void fireTournamentStart() {
        for (var h : tournamentStartHooks) { try { h.run(); } catch (Exception ignored) {} }
    }

    /**
     * Fire the game-start hook. Called by KMCCore's GameManager.startGame
     * after the game has been registered as active. Each registered hook
     * is called with the game id; minigame plugins filter by their own id.
     */
    public void fireGameStart(String gameId) {
        for (var h : gameStartHooks) {
            try { h.accept(gameId); }
            catch (Exception e) {
                plugin.getLogger().warning("onGameStart hook threw: " + e.getMessage());
            }
        }
    }

    // ---- Scoreboard ownership -------------------------------------

    /**
     * Acquire scoreboard "ownership" — prevents multiple minigame
     * scoreboards from fighting over the same player view. Returns
     * true if the lock was acquired, false if another owner has it.
     *
     * <p>The owner string is purely informational ("ae", "quakecraft", etc.).
     */
    public boolean acquireScoreboard(String owner) {
        if (scoreboardOwner != null && !scoreboardOwner.equals(owner)) {
            plugin.getLogger().warning("Scoreboard already owned by '" + scoreboardOwner
                    + "', '" + owner + "' tried to acquire");
            return false;
        }
        scoreboardOwner = owner;
        return true;
    }

    /** Release scoreboard ownership. Call on game cleanup. */
    public void releaseScoreboard(String owner) {
        if (scoreboardOwner != null && scoreboardOwner.equals(owner)) {
            scoreboardOwner = null;
        }
    }

    public String getScoreboardOwner() { return scoreboardOwner; }

    // ---- End-of-game stats helper ---------------------------------

    /**
     * Records standard end-of-game player stats: increments games played,
     * adds a win (or resets streak) based on whether the player won.
     *
     * <p>Call this from each minigame's end-of-game routine for every
     * participant. Standardizes the per-player tournament stat update
     * across all minigames.
     *
     * @param uuid    participant UUID
     * @param name    display name (used if the PlayerData record needs creating)
     * @param gameId  game id (e.g. "adventure_escape", "quake_craft")
     * @param won     true if this player came in 1st place
     */
    public void recordGameParticipation(UUID uuid, String name, String gameId, boolean won) {
        var pd = plugin.getPlayerDataManager().getOrCreate(uuid, name);
        pd.incrementGamesPlayed();
        if (won) pd.addWin(gameId);
        else     pd.resetStreak();
    }
}
