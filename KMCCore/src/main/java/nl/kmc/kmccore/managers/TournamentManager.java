package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.util.AnnouncementUtil;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tournament lifecycle manager.
 *
 * <p>FIX: {@link #hardReset()} now also wipes all team memberships,
 * not just points. Members get removed from every team and their
 * playerdata.teamId is cleared.
 */
public class TournamentManager {

    private final KMCCore plugin;
    private boolean active;
    private int     currentRound;
    private int     totalRounds;

    public TournamentManager(KMCCore plugin) {
        this.plugin      = plugin;
        this.totalRounds = plugin.getConfig().getInt("tournament.total-rounds", 5);
        load();
    }

    private void load() {
        active = Boolean.parseBoolean(plugin.getDatabaseManager()
                .getTournamentValue("active",
                        String.valueOf(plugin.getConfig().getBoolean("tournament.active", false))));
        currentRound = Integer.parseInt(plugin.getDatabaseManager()
                .getTournamentValue("current_round",
                        String.valueOf(plugin.getConfig().getInt("tournament.current-round", 1))));
    }

    public void save() {
        plugin.getDatabaseManager().setTournamentValue("active",        String.valueOf(active));
        plugin.getDatabaseManager().setTournamentValue("current_round", String.valueOf(currentRound));
    }

    public boolean start() {
        if (active) return false;
        active       = true;
        currentRound = 1;
        save();
        AnnouncementUtil.broadcastTitle(plugin, "announcements.tournament-start", null);
        Bukkit.broadcastMessage(MessageUtil.get("broadcast.tournament-start"));
        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAll();
        return true;
    }

    public boolean stop() {
        if (!active) return false;
        active = false;
        save();
        Bukkit.broadcastMessage(MessageUtil.color(
                plugin.getConfig().getString("settings.prefix", "&6[KMC] ")
                + "&cHet toernooi is gestopt."));
        plugin.getScoreboardManager().refreshAll();
        return true;
    }

    public void endTournament() {
        active = false;
        save();

        KMCTeam winner = plugin.getTeamManager().getTeamsSortedByPoints().stream()
                .findFirst().orElse(null);
        String winnerName = winner != null ? winner.getColor() + winner.getDisplayName() : "Onbekend";

        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══════════════════════════"));
        Bukkit.broadcastMessage(MessageUtil.color("&6&l   TOERNOOI AFGELOPEN!"));
        Bukkit.broadcastMessage(MessageUtil.color("&eWinnaar: " + winnerName));
        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══════════════════════════"));

        plugin.getDatabaseManager().resetAll(false);
        plugin.getTeamManager().resetScores();
        plugin.getPlayerDataManager().resetSeasonStats();
        plugin.getGameManager().resetPlayedGames();
        currentRound = 1;
        save();

        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAll();
        Bukkit.broadcastMessage(MessageUtil.color("&aAlle punten zijn gereset. Klaar voor het volgende toernooi!"));
    }

    public boolean nextRound() {
        if (currentRound >= totalRounds) return false;
        currentRound++;
        save();
        double mul = plugin.getPointsManager().getMultiplierForRound(currentRound);
        AnnouncementUtil.broadcastTitle(plugin, "announcements.round-start",
                new String[]{"{round}", "{multiplier}"},
                new String[]{String.valueOf(currentRound), String.valueOf(mul)});
        Bukkit.broadcastMessage(MessageUtil.get("broadcast.round-start")
                .replace("{round}", String.valueOf(currentRound))
                .replace("{multiplier}", String.valueOf(mul)));
        plugin.getScoreboardManager().refreshAll();
        return true;
    }

    public boolean setRound(int round) {
        if (round < 1 || round > totalRounds) return false;
        currentRound = round;
        save();
        plugin.getScoreboardManager().refreshAll();
        return true;
    }

    /** Soft reset — zero points/stats, keep lifetime stats + team assignments. */
    public void reset() {
        active       = false;
        currentRound = 1;
        save();
        plugin.getDatabaseManager().resetAll(false);
        plugin.getTeamManager().resetScores();
        plugin.getPlayerDataManager().resetSeasonStats();
        plugin.getGameManager().resetPlayedGames();
        plugin.getScoreboardManager().refreshAll();
    }

    /**
     * Hard reset: wipes EVERYTHING including team memberships.
     *
     * <p>Steps:
     * <ol>
     *   <li>Kick every player out of every team</li>
     *   <li>Zero all team points/wins</li>
     *   <li>Wipe all player stats including lifetime</li>
     *   <li>Clear tournament played-games log</li>
     *   <li>Refresh nametags and sidebar</li>
     * </ol>
     */
    public void hardReset() {
        active       = false;
        currentRound = 1;
        save();

        // 1. Remove every player from every team
        for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
            List<UUID> members = new ArrayList<>(t.getMembers()); // avoid concurrent mod
            for (UUID uuid : members) {
                plugin.getTeamManager().removePlayerFromTeam(uuid);
            }
        }

        // 2-4. Wipe data
        plugin.getDatabaseManager().resetAll(true);
        plugin.getTeamManager().resetScores();
        plugin.getPlayerDataManager().resetAll();
        plugin.getGameManager().resetPlayedGames();

        // 5. Refresh everything visible
        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAllNametags();
        plugin.getTabListManager().refreshAll();
    }

    public boolean isActive()        { return active; }
    public int     getCurrentRound() { return currentRound; }
    public int     getTotalRounds()  { return totalRounds; }
    public double  getMultiplier()   { return plugin.getPointsManager().getMultiplierForRound(currentRound); }
}
