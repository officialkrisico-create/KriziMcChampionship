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

import java.util.*;
import java.util.logging.Level;

/**
 * Per-player sidebar.
 *
 * <p>FIXES IN THIS VERSION:
 * <ul>
 *   <li><b>No more flicker.</b> Previously each tick we unregistered the
 *       objective and re-created it — leaving a one-tick gap where the
 *       sidebar was invisible. Now we keep TWO objectives ("kmc_a" and
 *       "kmc_b"), only one shown at a time. We populate the hidden one,
 *       then atomically swap which one is on the sidebar slot. No gap.</li>
 *   <li><b>Top 5 only</b> instead of all teams.</li>
 *   <li><b>Viewer's own points</b> always shown, regardless of team.</li>
 * </ul>
 */
public class ScoreboardManager {

    private static final int  MAX_SIDEBAR_LINES = 15;
    private static final int  TOP_TEAM_COUNT    = 5;

    private final KMCCore plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    /** Tracks which buffer ("a" or "b") is currently visible per player. */
    private final Map<UUID, String> activeBuffer = new HashMap<>();
    private BukkitTask updateTask;

    public ScoreboardManager(KMCCore plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            int interval = plugin.getConfig().getInt("scoreboard.update-interval", 20);
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 40L, interval);
        }
    }

    private void tickAll() {
        if (plugin.getApi().isScoreboardOwnedByMinigame()) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                updatePlayer(p);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Scoreboard update failed for " + p.getName() + " — skipping", e);
            }
        }
    }

    public void refreshAll() { tickAll(); }

    public void forceRefreshPlayer(Player player) {
        try { updatePlayer(player); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Forced refresh failed", e); }
    }

    // ----------------------------------------------------------------

    public void updatePlayer(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        boolean newBoard = false;

        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            boards.put(player.getUniqueId(), board);
            newBoard = true;
        }

        if (newBoard) {
            plugin.getTabListManager().registerPersonalBoard(player.getUniqueId(), board);
        }

        // Determine which buffer is currently shown vs hidden
        String currentName = activeBuffer.getOrDefault(player.getUniqueId(), "kmc_b");
        String nextName    = currentName.equals("kmc_a") ? "kmc_b" : "kmc_a";

        // Wipe + rebuild the hidden buffer
        Objective next = board.getObjective(nextName);
        if (next != null) {
            try { next.unregister(); } catch (Exception ignored) {}
        }
        next = board.registerNewObjective(nextName, Criteria.DUMMY,
                MessageUtil.color(plugin.getConfig().getString("scoreboard.title", "&6&lKMC")));

        List<String> lines = buildLines(player);
        if (lines.size() > MAX_SIDEBAR_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_SIDEBAR_LINES));
        }

        int score = lines.size();
        Set<String> usedEntries = new HashSet<>();
        for (String text : lines) {
            String entry = uniqueEntry(text, usedEntries);
            usedEntries.add(entry);
            next.getScore(entry).setScore(score);
            score--;
        }

        // ATOMIC SWAP: put the freshly-built buffer on the sidebar slot.
        // The previously-shown buffer is now hidden but still alive — we'll
        // overwrite it on the next tick. No gap, no flicker.
        next.setDisplaySlot(DisplaySlot.SIDEBAR);
        activeBuffer.put(player.getUniqueId(), nextName);

        player.setScoreboard(board);
    }

    // ----------------------------------------------------------------
    // Sidebar content
    // ----------------------------------------------------------------

    private List<String> buildLines(Player player) {
        List<String> lines = new ArrayList<>();

        boolean active = plugin.getTournamentManager().isActive();
        int     round  = plugin.getTournamentManager().getCurrentRound();
        double  mul    = plugin.getTournamentManager().getMultiplier();

        String gameName = plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getDisplayName()
                : null;

        KMCTeam myTeam = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        PlayerData pd  = plugin.getPlayerDataManager().get(player.getUniqueId());
        // If for some reason cache is empty, fall back to DB so points always show
        if (pd == null) pd = plugin.getDatabaseManager().loadPlayer(player.getUniqueId());

        List<KMCTeam> allTeams = plugin.getTeamManager().getTeamsSortedByPoints();

        // --- Status section ---
        if (active) {
            lines.add("&7Ronde: &e" + round + " &8(&e×" + mul + "&8)");
        } else {
            lines.add("&7Status: &cInactief");
        }
        if (gameName != null) lines.add("&7Game: &b" + gameName);

        lines.add("&r");

        // --- Top 5 teams ---
        if (!allTeams.isEmpty()) {
            lines.add("&6&lTop Teams:");
            int show = Math.min(TOP_TEAM_COUNT, allTeams.size());
            for (int i = 0; i < show; i++) {
                KMCTeam t = allTeams.get(i);
                String rank;
                if      (i == 0) rank = "&6#1";
                else if (i == 1) rank = "&7#2";
                else if (i == 2) rank = "&c#3";
                else             rank = "&8#" + (i + 1);
                lines.add(rank + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints());
            }
        }

        lines.add("&r ");

        // --- Your team (if any) ---
        if (myTeam != null) {
            lines.add("&e&lJouw Team:");
            lines.add(myTeam.getColor() + myTeam.getDisplayName()
                    + " &8- &e" + myTeam.getPoints() + "p");
        }

        // --- Always show your personal points ---
        lines.add("&r  ");
        lines.add("&b&lJouw Punten: &e" + (pd != null ? pd.getPoints() : 0));

        return lines;
    }

    // ----------------------------------------------------------------

    private static final ChatColor[] UNIQUE_COLORS = ChatColor.values();

    private String uniqueEntry(String text, Set<String> taken) {
        String colored = MessageUtil.color(text);
        for (int i = 0; i < UNIQUE_COLORS.length; i++) {
            String prefix = UNIQUE_COLORS[i].toString() + ChatColor.RESET;
            String entry = prefix + colored;
            if (entry.length() > 40) entry = entry.substring(0, 40);
            if (!taken.contains(entry)) return entry;
        }
        return colored;
    }

    // ----------------------------------------------------------------

    public void onPlayerJoin(Player player) {
        if (plugin.getApi().isScoreboardOwnedByMinigame()) return;
        try { updatePlayer(player); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Scoreboard onJoin failed", e); }
    }

    public void onPlayerQuit(Player player) {
        boards.remove(player.getUniqueId());
        activeBuffer.remove(player.getUniqueId());
        plugin.getTabListManager().unregisterPersonalBoard(player.getUniqueId());
    }

    public void cleanup() {
        if (updateTask != null) updateTask.cancel();
        boards.clear();
        activeBuffer.clear();
    }
}
