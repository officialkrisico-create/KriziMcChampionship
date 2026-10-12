package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.AnnouncementUtil;
import nl.kmc.kmccore.util.EventStatsBookBuilder;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tournament lifecycle manager.
 *
 * <p>NEW IN THIS VERSION:
 * <ul>
 *   <li>{@code event_number} stored in the tournament_state table —
 *       auto-increments on every tournament start so the post-event
 *       book says "KMC #1", "KMC #2", etc.</li>
 *   <li>{@link #endTournament()} now schedules the post-event book
 *       distribution: title screen for winning team → 5s later top 3
 *       players announcement → 3s later signed book in everyone's
 *       inventory.</li>
 * </ul>
 */
public class TournamentManager {

    private final KMCCore plugin;
    private boolean active;
    private int     currentRound;
    private int     totalRounds;

    /** The one round this tournament that gets a surprise Golden Hour (picked fresh on every start()). */
    private Integer goldenRound;

    /** When the running tournament started (epoch ms), for the history record. */
    private long startedAtMs = System.currentTimeMillis();

    /** Set by the finale: this team is the champion regardless of the points table. */
    private String championTeamId;
    /** Set by the finale: the beaten finalist (shown as runner-up). */
    private String runnerUpTeamId;
    private final java.util.Random random = new java.util.Random();

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

    // ----------------------------------------------------------------
    // Event number
    // ----------------------------------------------------------------

    public int getEventNumber() {
        try {
            return Integer.parseInt(plugin.getDatabaseManager()
                    .getTournamentValue("event_number", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void incrementEventNumber() {
        int next = getEventNumber() + 1;
        plugin.getDatabaseManager().setTournamentValue("event_number", String.valueOf(next));
        plugin.getLogger().info("Starting KMC event #" + next);
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public boolean start() {
        if (active) return false;
        active       = true;
        currentRound = 1;
        championTeamId = null;
        runnerUpTeamId = null;
        if (plugin.getPointsLedger() != null) plugin.getPointsLedger().clear();   // a fresh book for a new event
        startedAtMs = System.currentTimeMillis();
        nl.kmc.kmccore.tournament.EventEndSequence.closeFeedback();
        incrementEventNumber();   // KMC #1, #2, ...
        plugin.getPointsManager().deactivateGoldenHour();
        goldenRound = plugin.getConfig().getBoolean("golden-hour.enabled", true)
                ? 1 + random.nextInt(Math.max(1, totalRounds)) : null;
        save();

        AnnouncementUtil.broadcastTitle(plugin, "announcements.tournament-start", null);
        Bukkit.broadcastMessage(MessageUtil.get("broadcast.tournament-start"));
        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAll();
        plugin.getApi().fireTournamentStart();
        return true;
    }

    /**
     * If the current round is this tournament's (secretly pre-picked) Golden
     * Hour round, activates it and reveals it with fanfare — a no-op if it's
     * not this round, or if it already fired.
     *
     * <p><b>Call this right before the round's first game actually starts</b>
     * (e.g. from {@code AutomationManager} once its whole ceremony sequence —
     * opening, team showcase, intermission, voting, countdown — has finished).
     * Calling it from {@link #start()}/{@link #nextRound()} themselves fires
     * it too early: those run instantly, long before the ceremony reaches the
     * point where a round "really" begins for the players watching, so the
     * reveal would visually collide with the opening ceremony.
     */
    public void revealGoldenHourIfDue() {
        if (goldenRound == null || currentRound != goldenRound) return;
        double multiplier = plugin.getConfig().getDouble("golden-hour.multiplier", 2.0);
        plugin.getPointsManager().activateGoldenHour(multiplier);
        goldenRound = null; // one-shot — never trigger again this tournament

        Bukkit.broadcastMessage(MessageUtil.color("&e&m                                        "));
        Bukkit.broadcastMessage(MessageUtil.color("        &6&l✨ GOLDEN HOUR ✨"));
        Bukkit.broadcastMessage(MessageUtil.color("  &eAlle punten deze ronde tellen &6×" + multiplier + "&e!"));
        Bukkit.broadcastMessage(MessageUtil.color("&e&m                                        "));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(MessageUtil.color("&6&l✨ GOLDEN HOUR ✨"), MessageUtil.color("&e×" + multiplier + " punten!"), 10, 70, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.3f);
        }
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
    /**
     * Ends the tournament: runs the whole closing sequence (announcement, podium, awards, credits, fan
     * favorite, feedback, statsbooks — see {@link nl.kmc.kmccore.tournament.EventEndSequence}) and only
     * then resets points and stats.
     */
    public void endTournament() {
        active = false;
        goldenRound = null;
        plugin.getPointsManager().deactivateGoldenHour();
        save();

        int eventNumber = getEventNumber();
        nl.kmc.kmccore.tournament.EventEndSequence.run(plugin, eventNumber, () -> {
            plugin.getDatabaseManager().resetAll(false);
            plugin.getTeamManager().resetScores();
            plugin.getPlayerDataManager().resetSeasonStats();
            plugin.getGameManager().resetPlayedGames();
            currentRound = 1;
            save();

            plugin.getScoreboardManager().refreshAll();
            plugin.getTabListManager().refreshAll();
            Bukkit.broadcastMessage(MessageUtil.color(
                    "&aPunten gereset. Klaar voor het volgende toernooi!"));

            plugin.getApi().fireTournamentEnd();
        });
    }


    // ----------------------------------------------------------------
    // Rounds
    // ----------------------------------------------------------------

    public boolean nextRound() {
        if (currentRound >= totalRounds) return false;
        plugin.getPointsManager().deactivateGoldenHour(); // clear last round's boost before moving on
        currentRound++;
        save();
        // NOTE: Golden Hour is NOT revealed here — see revealGoldenHourIfDue().
        // This broadcast always shows the round's own plain multiplier; the
        // Golden Hour reveal (if this is the round) comes later with its own
        // distinct announcement, once the ceremony sequence actually reaches
        // the real game start.
        double mul = plugin.getPointsManager().getCurrentMultiplier();
        AnnouncementUtil.broadcastTitle(plugin, "announcements.round-start",
                new String[]{"{round}", "{multiplier}"},
                new String[]{String.valueOf(currentRound), String.valueOf(mul)});
        Bukkit.broadcastMessage(MessageUtil.get("broadcast.round-start")
                .replace("{round}", String.valueOf(currentRound))
                .replace("{multiplier}", String.valueOf(mul)));
        plugin.getScoreboardManager().refreshAll();
        plugin.getApi().fireRoundStart(currentRound);
        return true;
    }

    /** Changes the number of rounds (a /kmcauto preset or game count). The multiplier ladder keeps its last step. */
    public void setTotalRounds(int rounds) {
        this.totalRounds = Math.max(1, rounds);
        plugin.getConfig().set("tournament.total-rounds", this.totalRounds);
        plugin.saveConfig();
        if (currentRound > this.totalRounds) currentRound = this.totalRounds;
    }

    /** Finale result: this team wins the tournament regardless of the points table. */
    public void setChampion(String teamId, String runnerUpId) {
        this.championTeamId = teamId;
        this.runnerUpTeamId = runnerUpId;
    }

    public long   getStartedAtMs()    { return startedAtMs; }
    public String getChampionTeamId() { return championTeamId; }
    public String getRunnerUpTeamId() { return runnerUpTeamId; }

    /**
     * The final standings: champion first, then the beaten finalist, then everybody else by points.
     * Without a finale this is simply the points table.
     */
    public java.util.List<KMCTeam> getFinalStandings() {
        java.util.List<KMCTeam> table = new java.util.ArrayList<>(plugin.getTeamManager().getTeamsSortedByPoints());
        if (championTeamId == null) return table;
        java.util.List<KMCTeam> out = new java.util.ArrayList<>();
        KMCTeam champ = plugin.getTeamManager().getTeam(championTeamId);
        KMCTeam runner = runnerUpTeamId != null ? plugin.getTeamManager().getTeam(runnerUpTeamId) : null;
        if (champ != null) out.add(champ);
        if (runner != null && runner != champ) out.add(runner);
        for (KMCTeam t : table) if (!out.contains(t)) out.add(t);
        return out;
    }

    public boolean setRound(int round) {
        if (round < 1 || round > totalRounds) return false;
        currentRound = round;
        save();
        plugin.getScoreboardManager().refreshAll();
        return true;
    }

    // ----------------------------------------------------------------
    // Resets
    // ----------------------------------------------------------------

    public void reset() {
        active       = false;
        currentRound = 1;
        goldenRound  = null;
        championTeamId = null;
        runnerUpTeamId = null;
        plugin.getPointsManager().deactivateGoldenHour();
        save();
        plugin.getDatabaseManager().resetAll(false);
        plugin.getTeamManager().resetScores();
        plugin.getPlayerDataManager().resetSeasonStats();
        plugin.getGameManager().resetPlayedGames();
        plugin.getScoreboardManager().refreshAll();
    }

    public void hardReset() {
        active       = false;
        currentRound = 1;
        goldenRound  = null;
        championTeamId = null;
        runnerUpTeamId = null;
        plugin.getPointsManager().deactivateGoldenHour();
        save();
        for (KMCTeam t : plugin.getTeamManager().getAllTeams()) {
            List<UUID> members = new ArrayList<>(t.getMembers());
            for (UUID uuid : members) plugin.getTeamManager().removePlayerFromTeam(uuid);
        }
        plugin.getDatabaseManager().resetAll(true);
        plugin.getTeamManager().resetScores();
        plugin.getPlayerDataManager().resetAll();
        plugin.getGameManager().resetPlayedGames();
        // Hard reset clears event number too
        plugin.getDatabaseManager().setTournamentValue("event_number", "0");
        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAllNametags();
        plugin.getTabListManager().refreshAll();
    }

    public boolean isActive()        { return active; }
    public int     getCurrentRound() { return currentRound; }
    public int     getTotalRounds()  { return totalRounds; }
    public double  getMultiplier()   { return plugin.getPointsManager().getCurrentMultiplier(); }
}
