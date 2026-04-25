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
 * <p>NEW IN THIS VERSION:
 * <ul>
 *   <li>{@link #onGameStart(Consumer)} — register hook for game start</li>
 *   <li>{@link #fireGameStart(String)} — KMCCore fires this when a game launches</li>
 * </ul>
 *
 * <p>Used by minigames like Adventure Escape to auto-start their lobby
 * countdown when KMCCore picks them as the next game.
 */
public class KMCApi {

    private final KMCCore plugin;

    private final List<BiConsumer<String, String>> gameEndHooks         = new ArrayList<>();
    private final List<Consumer<String>>           gameStartHooks       = new ArrayList<>();
    private final List<Consumer<Integer>>          roundStartHooks      = new ArrayList<>();
    private final List<Runnable>                   tournamentStartHooks = new ArrayList<>();
    private final List<Runnable>                   tournamentEndHooks   = new ArrayList<>();

    private volatile String scoreboardOwner;

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
    public String getActiveGameId() {
        return plugin.getGameManager().getActiveGame() != null
               ? plugin.getGameManager().getActiveGame().getId() : null;
    }
    public double  getCurrentMultiplier() { return plugin.getTournamentManager().getMultiplier(); }
    public int     getCurrentRound()      { return plugin.getTournamentManager().getCurrentRound(); }
    public boolean isTournamentActive()   { return plugin.getTournamentManager().isActive(); }

    // ---- Point mutations -------------------------------------------

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

    // ---- Scoreboard ownership lock --------------------------------

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

    public void releaseScoreboard(String minigameName) {
        if (minigameName == null) return;
        if (!minigameName.equals(scoreboardOwner)) {
            plugin.getLogger().warning("Release called by '" + minigameName
                    + "' but owner is '" + scoreboardOwner + "' — ignored.");
            return;
        }
        scoreboardOwner = null;
        plugin.getLogger().info("Scoreboard released by " + minigameName);
        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAll();
    }

    public boolean isScoreboardOwnedByMinigame() { return scoreboardOwner != null; }
    public String  getScoreboardOwner()          { return scoreboardOwner; }

    // ---- Hooks (registration) --------------------------------------

    /** Fires when a game starts. Argument: gameId (e.g. "adventure_escape"). */
    public void onGameStart(Consumer<String> hook)              { gameStartHooks.add(hook); }
    public void onGameEnd(BiConsumer<String, String> hook)      { gameEndHooks.add(hook); }
    public void onRoundStart(Consumer<Integer> hook)            { roundStartHooks.add(hook); }
    public void onTournamentStart(Runnable hook)                { tournamentStartHooks.add(hook); }
    public void onTournamentEnd(Runnable hook)                  { tournamentEndHooks.add(hook); }

    // ---- Hooks (firing — internal use) -----------------------------

    public void fireGameStart(String gameId) {
        for (var h : gameStartHooks) { try { h.accept(gameId); } catch (Exception ignored) {} }
    }
    public void fireGameEnd(String gameName, String winner) {
        for (var h : gameEndHooks) { try { h.accept(gameName, winner); } catch (Exception ignored) {} }
    }
    public void fireRoundStart(int round) {
        for (var h : roundStartHooks) { try { h.accept(round); } catch (Exception ignored) {} }
    }
    public void fireTournamentStart() {
        for (var h : tournamentStartHooks) { try { h.run(); } catch (Exception ignored) {} }
    }
    public void fireTournamentEnd() {
        for (var h : tournamentEndHooks) { try { h.run(); } catch (Exception ignored) {} }
    }
}
