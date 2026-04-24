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

    public void fireGameEnd(String gameName, String winner) {
        for (var h : gameEndHooks) { try { h.accept(gameName, winner); } catch (Exception ignored) {} }
    }
    public void fireRoundStart(int round) {
        for (var h : roundStartHooks) { try { h.accept(round); } catch (Exception ignored) {} }
    }
    public void fireTournamentStart() {
        for (var h : tournamentStartHooks) { try { h.run(); } catch (Exception ignored) {} }
    }
}
