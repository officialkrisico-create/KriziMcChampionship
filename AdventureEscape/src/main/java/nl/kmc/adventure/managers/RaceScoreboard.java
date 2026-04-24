package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.RacerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Per-player sidebar showing race standings during an active race.
 *
 * <p>Each player gets their own Scoreboard with:
 * <ul>
 *   <li>Current leader (who is 1st right now)</li>
 *   <li>Top 5 racers by progress (laps + current lap time)</li>
 *   <li>Player's own lap count, current lap time, best lap time</li>
 * </ul>
 *
 * <p>Refreshes every 0.5 seconds during active race.
 * Cleaned up at end of race.
 */
public class RaceScoreboard {

    private final AdventureEscapePlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public RaceScoreboard(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------

    public void refresh() {
        if (!plugin.getRaceManager().isActive()) return;

        List<RacerData> ranked = plugin.getRaceManager().getRankedRacers();
        if (ranked.isEmpty()) return;

        RacerData leader = ranked.get(0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = boards.computeIfAbsent(player.getUniqueId(),
                    u -> Bukkit.getScoreboardManager().getNewScoreboard());

            Objective obj = board.getObjective("ae_race");
            if (obj != null) obj.unregister();
            obj = board.registerNewObjective("ae_race", Criteria.DUMMY,
                    color("&6&l🏁 Adventure Escape"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            RacerData me = plugin.getRaceManager().getRacers().get(player.getUniqueId());

            int line = 15;
            line = set(obj, line, "&r");

            // Leader section
            line = set(obj, line, "&6&l⭐ Leider");
            String leaderName = leader.getName();
            line = set(obj, line, "&e" + leaderName);
            if (leader.hasFinished()) {
                line = set(obj, line, "&a&lFINISHED!");
            } else {
                line = set(obj, line, "&7Lap &f" + leader.getLapsCompleted()
                        + "&7/&f" + plugin.getArenaManager().getLaps());
                line = set(obj, line, "&7Lap tijd: &b" + RacerData.formatMs(leader.getCurrentLapMs()));
            }

            line = set(obj, line, "&r ");

            // Top 5 section
            line = set(obj, line, "&a&lTop Racers:");
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                RacerData rd = ranked.get(i);
                String medal = i == 0 ? "&6#1" : i == 1 ? "&7#2" : i == 2 ? "&c#3" : "&7#" + (i + 1);
                String status;
                if (rd.hasFinished()) {
                    status = "&a" + RacerData.formatMs(rd.getTotalTimeMs());
                } else {
                    status = "&bL" + rd.getLapsCompleted();
                }
                line = set(obj, line, medal + " &f" + rd.getName() + " " + status);
            }

            line = set(obj, line, "&r  ");

            // Personal section
            if (me != null) {
                line = set(obj, line, "&b&lJij");
                if (me.hasFinished()) {
                    line = set(obj, line, "&aGefinisht: &e#" + me.getPlacement());
                    line = set(obj, line, "&7Tijd: &e" + RacerData.formatMs(me.getTotalTimeMs()));
                } else {
                    line = set(obj, line, "&7Lap: &f" + me.getLapsCompleted()
                            + "&7/&f" + plugin.getArenaManager().getLaps());
                    line = set(obj, line, "&7Huidige: &b" + RacerData.formatMs(me.getCurrentLapMs()));
                    if (me.getBestLapMs() > 0) {
                        line = set(obj, line, "&7Beste: &a" + RacerData.formatMs(me.getBestLapMs()));
                    }
                }
            }

            player.setScoreboard(board);
        }
    }

    /** Clears all personal scoreboards after race ends. */
    public void cleanup() {
        for (UUID uuid : boards.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                // Restore KMCCore's main scoreboard
                Scoreboard main = plugin.getKmcCore().getTabListManager().getMainScoreboard();
                p.setScoreboard(main);
            }
        }
        boards.clear();
    }

    // ----------------------------------------------------------------

    private int set(Objective obj, int score, String text) {
        String prefix = ChatColor.values()[score % ChatColor.values().length].toString()
                + ChatColor.RESET;
        String entry  = prefix + color(text);
        if (entry.length() > 40) entry = entry.substring(0, 40);
        obj.getScore(entry).setScore(score);
        return score - 1;
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
