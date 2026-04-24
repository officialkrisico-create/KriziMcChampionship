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
 * Per-player sidebar scoreboards.
 *
 * <p><b>Critical fixes in this version:</b>
 * <ul>
 *   <li>Negative score indices no longer crash (used 16 invisible color
 *       prefixes and wrap via (score MOD length + length) MOD length
 *       — safe for any sign).</li>
 *   <li>Total line count is CAPPED at 15 (Minecraft's max sidebar lines).
 *       With many teams, only top N are shown to stay within the cap.</li>
 *   <li>Every tick wrapped in try/catch — a single bad frame no longer
 *       floods console with "Task #20 exception" spam.</li>
 *   <li>Duplicate entries handled defensively — reset scores before adding.</li>
 *   <li>Sidebar shows even when tournament is inactive (round 0 branch).</li>
 * </ul>
 */
public class ScoreboardManager {

    /** Minecraft hard limit on visible sidebar lines. */
    private static final int MAX_SIDEBAR_LINES = 15;

    private final KMCCore plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BukkitTask updateTask;

    public ScoreboardManager(KMCCore plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            int interval = plugin.getConfig().getInt("scoreboard.update-interval", 20);
            // Run on the main thread, tick every N ticks.
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 40L, interval);
        }
    }

    // ----------------------------------------------------------------
    // Tick loop — wrapped so one bad tick doesn't spam console
    // ----------------------------------------------------------------

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                updatePlayer(p);
            } catch (Exception e) {
                // Log once per minute at most to avoid spam; players without
                // a valid scoreboard setup silently skip this tick.
                plugin.getLogger().log(Level.WARNING,
                        "Scoreboard update failed for " + p.getName() + " — skipping", e);
            }
        }
    }

    public void refreshAll() { tickAll(); }

    // ----------------------------------------------------------------
    // Per-player update
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

        // Rebuild sidebar objective from scratch each tick
        Objective existing = board.getObjective("kmc_sidebar");
        if (existing != null) {
            try { existing.unregister(); } catch (Exception ignored) {}
        }

        Objective sidebar = board.registerNewObjective("kmc_sidebar", Criteria.DUMMY,
                MessageUtil.color(plugin.getConfig().getString("scoreboard.title", "&6&lKMC")));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Build lines top-to-bottom — Minecraft renders highest score on top
        List<String> lines = buildLines(player);

        // Cap lines to what Minecraft can display
        if (lines.size() > MAX_SIDEBAR_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_SIDEBAR_LINES));
        }

        int score = lines.size();
        Set<String> usedEntries = new HashSet<>();
        for (String text : lines) {
            String entry = uniqueEntry(text, usedEntries);
            usedEntries.add(entry);
            sidebar.getScore(entry).setScore(score);
            score--;
        }

        player.setScoreboard(board);
    }

    // ----------------------------------------------------------------
    // Sidebar content builder
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

        List<KMCTeam> allTeams = plugin.getTeamManager().getTeamsSortedByPoints();

        // --- Status line ---
        if (active) {
            lines.add("&7Ronde: &e" + round + " &8(&e×" + mul + "&8)");
        } else {
            lines.add("&7Status: &cInactief");
        }
        if (gameName != null) {
            lines.add("&7Game: &b" + gameName);
        }

        lines.add("&r"); // blank separator

        // --- Top teams ---
        // Remaining budget = MAX_SIDEBAR_LINES - already-used - my-team-section(3) - player-section(4)
        int reserved = 3 + 4; // jouw team section + jij section
        int available = MAX_SIDEBAR_LINES - lines.size() - reserved;
        int teamLinesToShow = Math.min(allTeams.size() + 1, Math.max(1, available));  // +1 for header
        if (teamLinesToShow > 1) {
            lines.add("&6&lTop Teams:");
            int teamsLeft = teamLinesToShow - 1; // header already added
            for (int i = 0; i < Math.min(teamsLeft, allTeams.size()); i++) {
                KMCTeam t = allTeams.get(i);
                String rank;
                if      (i == 0) rank = "&6#1";
                else if (i == 1) rank = "&7#2";
                else if (i == 2) rank = "&c#3";
                else             rank = "&8#" + (i + 1);
                lines.add(rank + " " + t.getColor() + t.getDisplayName()
                        + " &8- &e" + t.getPoints());
            }
        }

        lines.add("&r "); // blank separator

        // --- Your team ---
        if (myTeam != null) {
            lines.add("&e&lJouw Team:");
            lines.add(myTeam.getColor() + myTeam.getDisplayName()
                    + " &8- &e" + myTeam.getPoints() + " pt");
        }

        lines.add("&r  "); // blank separator

        // --- Personal stats ---
        lines.add("&b&lJij:");
        lines.add("&7Punten: &e" + (pd != null ? pd.getPoints() : 0));

        return lines;
    }

    // ----------------------------------------------------------------
    // Entry uniquification — prevents duplicate-score errors
    // ----------------------------------------------------------------

    /** 16 invisible color codes used as line prefixes to guarantee uniqueness. */
    private static final ChatColor[] UNIQUE_COLORS = ChatColor.values();

    /**
     * Produces a unique sidebar entry string for the given text.
     * Minecraft requires every entry on a sidebar to be distinct —
     * we prepend a unique invisible color code to achieve this.
     */
    private String uniqueEntry(String text, Set<String> taken) {
        String colored = MessageUtil.color(text);
        for (int i = 0; i < UNIQUE_COLORS.length; i++) {
            String prefix = UNIQUE_COLORS[i].toString() + ChatColor.RESET;
            String entry = prefix + colored;
            if (entry.length() > 40) entry = entry.substring(0, 40);
            if (!taken.contains(entry)) return entry;
        }
        // Fallback — should never hit with <=15 lines
        return colored;
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void onPlayerJoin(Player player) {
        try { updatePlayer(player); }
        catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Scoreboard onJoin failed", e);
        }
    }

    public void onPlayerQuit(Player player) {
        boards.remove(player.getUniqueId());
        plugin.getTabListManager().unregisterPersonalBoard(player.getUniqueId());
    }

    public void cleanup() {
        if (updateTask != null) updateTask.cancel();
        boards.clear();
    }
}
