package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
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
        incrementEventNumber();   // KMC #1, #2, ...
        save();

        AnnouncementUtil.broadcastTitle(plugin, "announcements.tournament-start", null);
        Bukkit.broadcastMessage(MessageUtil.get("broadcast.tournament-start"));
        plugin.getScoreboardManager().refreshAll();
        plugin.getTabListManager().refreshAll();
        plugin.getApi().fireTournamentStart();
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

    /**
     * Ends the tournament: announcements + book distribution + reset.
     */
    public void endTournament() {
        active = false;
        save();

        int eventNumber = getEventNumber();
        KMCTeam winner = plugin.getTeamManager().getTeamsSortedByPoints().stream()
                .findFirst().orElse(null);
        String winnerName = winner != null
                ? winner.getColor() + winner.getDisplayName()
                : "Onbekend";

        // Phase 1: big winner announcement
        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══════════════════════════"));
        Bukkit.broadcastMessage(MessageUtil.color("&6&l   KMC #" + eventNumber + " AFGELOPEN!"));
        Bukkit.broadcastMessage(MessageUtil.color("&eWinnaar: " + winnerName));
        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══════════════════════════"));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(
                    MessageUtil.color("&6&l🏆 KMC #" + eventNumber),
                    MessageUtil.color("Winnaar: " + winnerName),
                    10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }

        // Phase 2: top 3 players (5 seconds later)
        Bukkit.getScheduler().runTaskLater(plugin, () -> announceTopPlayers(), 100L);

        // Phase 3: hand out books (8 seconds later)
        Bukkit.getScheduler().runTaskLater(plugin, () -> handOutBooks(eventNumber), 160L);

        // Phase 4: reset season stats (10 seconds later, after books are made from the data)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
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
        }, 200L);
    }

    private void announceTopPlayers() {
        var top = plugin.getPlayerDataManager().getLeaderboard();
        if (top.isEmpty()) return;

        Bukkit.broadcastMessage(MessageUtil.color("&6&l═══ Top Spelers ═══"));
        String[] medals = {"&6🥇", "&7🥈", "&c🥉"};
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            PlayerData pd = top.get(i);
            Bukkit.broadcastMessage(MessageUtil.color(
                    medals[i] + " &f" + pd.getName() + " &8- &e" + pd.getPoints() + " punten"));
        }

        // Title for top player
        PlayerData best = top.get(0);
        Player bestOnline = Bukkit.getPlayer(best.getUuid());
        if (bestOnline != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(
                        MessageUtil.color("&6&l⭐ Beste Speler"),
                        MessageUtil.color("&e" + best.getName() + " &7(" + best.getPoints() + ")"),
                        10, 60, 20);
            }
        }
    }

    private void handOutBooks(int eventNumber) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                ItemStack book = EventStatsBookBuilder.buildBookFor(plugin, p, eventNumber);
                // Try to fit into inventory; if full, drop at feet
                var leftover = p.getInventory().addItem(book);
                if (!leftover.isEmpty()) {
                    p.getWorld().dropItem(p.getLocation(), book);
                    p.sendMessage(MessageUtil.color(
                            "&eJe inventaris is vol — het stat-boek ligt naast je."));
                } else {
                    p.sendMessage(MessageUtil.color(
                            "&a✦ Je hebt het KMC #" + eventNumber + " stat-boek ontvangen!"));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Book build failed for " + p.getName() + ": " + e.getMessage());
            }
        }
    }

    // ----------------------------------------------------------------
    // Rounds
    // ----------------------------------------------------------------

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
        plugin.getApi().fireRoundStart(currentRound);
        return true;
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
    public double  getMultiplier()   { return plugin.getPointsManager().getMultiplierForRound(currentRound); }
}
