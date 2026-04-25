package nl.kmc.adventure.managers;

import nl.kmc.adventure.AdventureEscapePlugin;
import nl.kmc.adventure.models.RacerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Per-player race sidebar.
 *
 * <p><b>Ownership integration:</b>
 * <ul>
 *   <li>{@link #start()} calls {@code KMCApi.acquireScoreboard("AdventureEscape")}
 *       to pause KMCCore's lobby refresh loop. No more flicker.</li>
 *   <li>{@link #cleanup()} calls {@code releaseScoreboard()} so the
 *       lobby sidebar comes back automatically when the race ends.</li>
 * </ul>
 *
 * <p>The race sidebar still ticks at 0.5s; the lock simply ensures
 * KMCCore's lobby manager stops competing for {@code player.setScoreboard()}.
 */
public class RaceScoreboard {

    private static final String OWNER_NAME = "AdventureEscape";

    private final AdventureEscapePlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    private boolean ownsLock = false;

    public RaceScoreboard(AdventureEscapePlugin plugin) { this.plugin = plugin; }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /**
     * Called by RaceManager when a race starts.
     * Acquires the scoreboard lock from KMCCore.
     */
    public void start() {
        if (!ownsLock) {
            boolean ok = plugin.getKmcCore().getApi().acquireScoreboard(OWNER_NAME);
            if (ok) ownsLock = true;
        }
    }

    /**
     * Called by RaceManager every race tick to update sidebar content.
     */
    public void refresh() {
        if (!plugin.getRaceManager().isActive()) return;

        // Ensure we own the lock (defensive — start() should have done this)
        if (!ownsLock) start();

        List<RacerData> ranked = plugin.getRaceManager().getRankedRacers();
        if (ranked.isEmpty()) return;

        RacerData leader = ranked.get(0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                paint(player, ranked, leader);
            } catch (Exception e) {
                plugin.getLogger().warning("RaceScoreboard paint failed for "
                        + player.getName() + ": " + e.getMessage());
            }
        }
    }

    private void paint(Player player, List<RacerData> ranked, RacerData leader) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(),
                u -> Bukkit.getScoreboardManager().getNewScoreboard());

        // Mirror KMCCore's team prefix data so chat/tab colours stay correct
        mirrorTeamsFromMain(board);

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
        line = set(obj, line, "&e" + leader.getName());
        if (leader.hasFinished()) {
            line = set(obj, line, "&a&lFINISHED!");
        } else {
            line = set(obj, line, "&7Lap &f" + leader.getLapsCompleted()
                    + "&7/&f" + plugin.getArenaManager().getLaps());
            line = set(obj, line, "&7Lap tijd: &b" + RacerData.formatMs(leader.getCurrentLapMs()));
        }

        line = set(obj, line, "&r ");

        // Top 5
        line = set(obj, line, "&a&lTop Racers:");
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            RacerData rd = ranked.get(i);
            String medal = i == 0 ? "&6#1" : i == 1 ? "&7#2" : i == 2 ? "&c#3" : "&7#" + (i + 1);
            String status = rd.hasFinished()
                    ? "&a" + RacerData.formatMs(rd.getTotalTimeMs())
                    : "&bL" + rd.getLapsCompleted();
            line = set(obj, line, medal + " &f" + rd.getName() + " " + status);
        }

        line = set(obj, line, "&r  ");

        // Personal
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

    /**
     * Copies KMCCore's main-scoreboard team registrations onto a personal
     * board so player tab/name colours stay correct during the race.
     */
    private void mirrorTeamsFromMain(Scoreboard personal) {
        Scoreboard main = plugin.getKmcCore().getTabListManager().getMainScoreboard();
        for (Team mainTeam : main.getTeams()) {
            String name = mainTeam.getName();
            // Match KMCCore's naming: "00_xxx" / "01_xxx" / ... or "zz_noteam"
            if (!name.equals("zz_noteam") && !name.matches("\\d\\d_.+")) continue;

            Team t = personal.getTeam(name);
            if (t == null) {
                try { t = personal.registerNewTeam(name); }
                catch (Exception e) { continue; }
            }
            t.prefix(mainTeam.prefix());
            // Paper 1.21+: mainTeam.color() returns TextColor; setter wants
            // NamedTextColor. Skip if it's not a NamedTextColor — the
            // prefix already encodes the colour visually.
            net.kyori.adventure.text.format.TextColor mc = mainTeam.color();
            if (mc instanceof net.kyori.adventure.text.format.NamedTextColor ntc) {
                t.color(ntc);
            }

            // Sync entries
            Set<String> mainEntries = mainTeam.getEntries();
            for (String entry : new HashSet<>(t.getEntries())) {
                if (!mainEntries.contains(entry)) t.removeEntry(entry);
            }
            for (String entry : mainEntries) {
                if (!t.hasEntry(entry)) t.addEntry(entry);
            }
        }
    }

    // ----------------------------------------------------------------

    /**
     * Called when the race ends.
     * Restores everyone to KMCCore's lobby scoreboard and releases the lock.
     */
    public void cleanup() {
        for (UUID uuid : boards.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                // Restore main scoreboard for tab nametags
                Scoreboard main = plugin.getKmcCore().getTabListManager().getMainScoreboard();
                p.setScoreboard(main);
            }
        }
        boards.clear();

        if (ownsLock) {
            plugin.getKmcCore().getApi().releaseScoreboard(OWNER_NAME);
            ownsLock = false;
        }
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
