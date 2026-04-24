package nl.kmc.kmccore.scoreboard;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player sidebar scoreboards.
 *
 * <p><b>FIX (tab colour only on own name):</b> Every new personal board
 * is now registered with TabListManager, which walks all registered
 * boards whenever team membership changes. This ensures every player
 * sees every other player's team prefix correctly.
 */
public class ScoreboardManager {

    private final KMCCore plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BukkitTask updateTask;

    public ScoreboardManager(KMCCore plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            int interval = plugin.getConfig().getInt("scoreboard.update-interval", 20);
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, interval, interval);
        }
    }

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) updatePlayer(p);
    }

    public void refreshAll() { tickAll(); }

    /**
     * Builds / refreshes the sidebar for a single player.
     */
    public void updatePlayer(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        boolean newBoard = false;

        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            boards.put(player.getUniqueId(), board);
            newBoard = true;
        }

        // CRITICAL: register this board with TabListManager so team colours sync
        if (newBoard) {
            plugin.getTabListManager().registerPersonalBoard(player.getUniqueId(), board);
        }

        // Rebuild sidebar objective every tick
        Objective existing = board.getObjective("kmc_sidebar");
        if (existing != null) existing.unregister();

        Objective sidebar = board.registerNewObjective("kmc_sidebar", Criteria.DUMMY,
                MessageUtil.color(plugin.getConfig().getString("scoreboard.title", "&6&lKMC Tournament")));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);

        boolean active = plugin.getTournamentManager().isActive();
        int     round  = plugin.getTournamentManager().getCurrentRound();
        double  mul    = plugin.getTournamentManager().getMultiplier();
        String  gameName = plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getDisplayName()
                : "&8Geen";

        KMCTeam myTeam = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        PlayerData pd  = plugin.getPlayerDataManager().get(player.getUniqueId());

        List<KMCTeam> allTeams = plugin.getTeamManager().getTeamsSortedByPoints();

        int line = 15;
        line = setLine(sidebar, line, "&r");

        if (!active) {
            line = setLine(sidebar, line, "&7Status: &cInactief");
        } else {
            line = setLine(sidebar, line, "&7Ronde: &e" + round + " &8(&e×" + mul + "&8)");
            line = setLine(sidebar, line, "&7Game: &b" + gameName);
        }

        line = setLine(sidebar, line, "&r ");
        line = setLine(sidebar, line, "&6&lTop Teams:");

        // Top 3
        for (int i = 0; i < Math.min(3, allTeams.size()); i++) {
            KMCTeam t = allTeams.get(i);
            String medal = i == 0 ? "&6#1" : i == 1 ? "&7#2" : "&c#3";
            line = setLine(sidebar, line, medal + " " + t.getColor()
                    + t.getDisplayName() + " &8- &e" + t.getPoints());
        }

        // Remaining teams
        if (allTeams.size() > 3) {
            for (int i = 3; i < allTeams.size(); i++) {
                KMCTeam t = allTeams.get(i);
                line = setLine(sidebar, line, "&8#" + (i + 1) + " "
                        + t.getColor() + t.getDisplayName() + " &8- &7" + t.getPoints());
            }
        }

        line = setLine(sidebar, line, "&r  ");
        line = setLine(sidebar, line, "&e&lJouw Team:");
        if (myTeam != null) {
            line = setLine(sidebar, line, myTeam.getColor() + myTeam.getDisplayName());
            line = setLine(sidebar, line, "&7Team punten: &e" + myTeam.getPoints());
        } else {
            line = setLine(sidebar, line, "&8Geen team");
        }

        line = setLine(sidebar, line, "&r   ");
        line = setLine(sidebar, line, "&b&lJij:");
        line = setLine(sidebar, line, "&7Punten: &e" + (pd != null ? pd.getPoints() : 0));
        line = setLine(sidebar, line, "&7Kills:  &c" + (pd != null ? pd.getKills()  : 0));
        line = setLine(sidebar, line, "&7Wins:   &a" + (pd != null ? pd.getWins()   : 0));

        player.setScoreboard(board);
    }

    public void onPlayerJoin(Player player) { updatePlayer(player); }

    public void onPlayerQuit(Player player) {
        boards.remove(player.getUniqueId());
        plugin.getTabListManager().unregisterPersonalBoard(player.getUniqueId());
    }

    public void cleanup() {
        if (updateTask != null) updateTask.cancel();
        boards.clear();
    }

    private int setLine(Objective obj, int score, String text) {
        String prefix = ChatColor.values()[score % ChatColor.values().length].toString()
                + ChatColor.RESET;
        String entry = prefix + MessageUtil.color(text);
        if (entry.length() > 40) entry = entry.substring(0, 40);
        obj.getScore(entry).setScore(score);
        return score - 1;
    }
}
