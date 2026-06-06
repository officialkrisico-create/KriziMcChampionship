package nl.kmc.kmccore.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import nl.kmc.kmccore.managers.TabListManager;

import java.util.*;
import java.util.logging.Level;

/**
 * Per-player sidebar + scoreboard team management.
 *
 * <p>Previously, scoreboard <em>creation</em> lived here while team-prefix
 * <em>syncing</em> lived in {@link TabListManager}, which held a redundant
 * {@code personalBoards} map pointing to the same {@link Scoreboard} objects.
 * Both maps are now unified here, eliminating the cross-manager coupling and
 * the duplicate storage.
 *
 * <p><b>Anti-flicker double buffer.</b> We keep two objectives ("kmc_a" / "kmc_b")
 * per player, only one shown at a time. We populate the hidden one, then
 * atomically swap which one is on the SIDEBAR slot. No one-tick gap.
 */
public class ScoreboardManager {

    private static final int MAX_SIDEBAR_LINES = 15;
    private static final int TOP_TEAM_COUNT    = 5;

    private static final String NO_TEAM_KEY = "zz_noteam";

    // ChatColor → Adventure NamedTextColor mapping (was duplicated in TabListManager)
    private static final Map<ChatColor, NamedTextColor> COLOR_MAP = new HashMap<>();
    static {
        COLOR_MAP.put(ChatColor.RED,          NamedTextColor.RED);
        COLOR_MAP.put(ChatColor.GOLD,         NamedTextColor.GOLD);
        COLOR_MAP.put(ChatColor.YELLOW,       NamedTextColor.YELLOW);
        COLOR_MAP.put(ChatColor.GREEN,        NamedTextColor.GREEN);
        COLOR_MAP.put(ChatColor.BLUE,         NamedTextColor.BLUE);
        COLOR_MAP.put(ChatColor.DARK_PURPLE,  NamedTextColor.DARK_PURPLE);
        COLOR_MAP.put(ChatColor.LIGHT_PURPLE, NamedTextColor.LIGHT_PURPLE);
        COLOR_MAP.put(ChatColor.WHITE,        NamedTextColor.WHITE);
        COLOR_MAP.put(ChatColor.DARK_GREEN,   NamedTextColor.DARK_GREEN);
        COLOR_MAP.put(ChatColor.AQUA,         NamedTextColor.AQUA);
        COLOR_MAP.put(ChatColor.DARK_AQUA,    NamedTextColor.DARK_AQUA);
        COLOR_MAP.put(ChatColor.DARK_RED,     NamedTextColor.DARK_RED);
        COLOR_MAP.put(ChatColor.DARK_BLUE,    NamedTextColor.DARK_BLUE);
        COLOR_MAP.put(ChatColor.GRAY,         NamedTextColor.GRAY);
        COLOR_MAP.put(ChatColor.DARK_GRAY,    NamedTextColor.DARK_GRAY);
    }

    private final KMCCore plugin;
    private final Scoreboard mainScoreboard;

    /** Single authoritative map: one Scoreboard per online player. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    /** Tracks which buffer ("kmc_a" or "kmc_b") is currently visible per player. */
    private final Map<UUID, String> activeBuffer = new HashMap<>();

    /** Per-game sidebar content, set while a game owns the scoreboard. */
    private volatile nl.kmc.core.api.GameScoreboard gameBoard;
    private volatile String gameBoardOwner;

    private BukkitTask updateTask;

    /** Called by the game API when a game wants to render its own sidebar. */
    public void setGameBoard(String gameId, nl.kmc.core.api.GameScoreboard board) {
        this.gameBoardOwner = gameId;
        this.gameBoard      = board;
    }

    /** Clears the per-game sidebar (only the current owner may clear it). */
    public void clearGameBoard(String gameId) {
        if (gameId != null && gameId.equals(gameBoardOwner)) {
            this.gameBoard      = null;
            this.gameBoardOwner = null;
        }
    }

    public ScoreboardManager(KMCCore plugin) {
        this.plugin = plugin;
        this.mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        ensureTeamsExist(mainScoreboard);

        if (plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            int interval = plugin.getConfig().getInt("scoreboard.update-interval", 20);
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 40L, interval);
        }
    }

    // ====================================================================
    // Tick / refresh
    // ====================================================================

    private void tickAll() {
        // While a game owns the board we keep ticking ONLY if it supplied a
        // per-game sidebar; otherwise leave the lobby sidebar as-is.
        if (plugin.getApi().isScoreboardOwnedByMinigame() && gameBoard == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { updatePlayer(p); }
            catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Scoreboard update failed for " + p.getName() + " — skipping", e);
            }
        }
    }

    /** Refresh sidebar for all online players. */
    public void refreshAll() {
        // Always sync team prefixes — safe during minigames (only touches Bukkit Team objects)
        syncAllBoards();
        tickAll();
    }

    public void refreshAllNametags() { syncAllBoards(); }

    public void forceRefreshPlayer(Player player) {
        try { updatePlayer(player); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Forced refresh failed", e); }
    }

    // ====================================================================
    // Per-player scoreboard update (sidebar)
    // ====================================================================

    public void updatePlayer(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        boolean newBoard = false;

        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            boards.put(player.getUniqueId(), board);
            newBoard = true;
        }

        if (newBoard) {
            ensureTeamsExist(board);
            syncTeamsOn(board);
        }

        // Decide what to paint: the per-game sidebar (if a game owns the board
        // and supplied one) or the normal lobby sidebar.
        boolean owned = plugin.getApi().isScoreboardOwnedByMinigame();
        String title;
        List<String> lines;
        if (owned) {
            if (gameBoard == null) return;            // game owns board, no custom sidebar → freeze
            title = MessageUtil.color(safeTitle(player));
            lines = safeLines(player);
            if (lines == null) return;               // game opted out for this tick → freeze
        } else {
            title = MessageUtil.color(plugin.getConfig().getString("scoreboard.title", "&6&lKMC"));
            lines = buildLines(player);
        }

        // Determine which buffer is visible vs hidden
        String currentName = activeBuffer.getOrDefault(player.getUniqueId(), "kmc_b");
        String nextName    = currentName.equals("kmc_a") ? "kmc_b" : "kmc_a";

        // Wipe + rebuild the hidden buffer
        Objective next = board.getObjective(nextName);
        if (next != null) {
            try { next.unregister(); } catch (Exception ignored) {}
        }
        next = board.registerNewObjective(nextName, Criteria.DUMMY, title);
        if (lines.size() > MAX_SIDEBAR_LINES)
            lines = new ArrayList<>(lines.subList(0, MAX_SIDEBAR_LINES));

        int score = lines.size();
        Set<String> usedEntries = new HashSet<>();
        for (String text : lines) {
            String entry = uniqueEntry(text, usedEntries);
            usedEntries.add(entry);
            next.getScore(entry).setScore(score--);
        }

        // ATOMIC SWAP: no sidebar gap, no flicker
        next.setDisplaySlot(DisplaySlot.SIDEBAR);
        activeBuffer.put(player.getUniqueId(), nextName);
        player.setScoreboard(board);
    }

    // ====================================================================
    // Team sync across all personal boards
    // ====================================================================

    /**
     * Syncs Bukkit team membership on every personal board and the main
     * scoreboard. Always safe to call — only touches shared Team objects,
     * not the sidebar objective.
     */
    public void syncAllBoards() {
        for (Scoreboard board : new ArrayList<>(boards.values())) {
            try { syncTeamsOn(board); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "syncTeamsOn failed", e); }
        }
        try { syncTeamsOn(mainScoreboard); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "syncTeamsOn(main) failed", e); }
    }

    private void ensureTeamsExist(Scoreboard board) {
        for (KMCTeam kmcTeam : plugin.getTeamManager().getTeamsInOrder()) {
            String teamKey = bukkitTeamName(kmcTeam);
            Team bt = board.getTeam(teamKey);
            if (bt == null) bt = board.registerNewTeam(teamKey);

            NamedTextColor ntc = COLOR_MAP.getOrDefault(kmcTeam.getColor(), NamedTextColor.WHITE);
            bt.prefix(Component.text("[" + kmcTeam.getDisplayName() + "] ", ntc, TextDecoration.BOLD));
            bt.color(ntc);
            bt.setAllowFriendlyFire(false);
            bt.setCanSeeFriendlyInvisibles(true);
        }

        Team none = board.getTeam(NO_TEAM_KEY);
        if (none == null) {
            none = board.registerNewTeam(NO_TEAM_KEY);
            none.prefix(Component.empty());
            none.color(NamedTextColor.GRAY);
        }

        // Remove any stale kmc_ prefix teams from previous plugin versions
        for (Team t : new ArrayList<>(board.getTeams())) {
            if (t.getName().startsWith("kmc_")) {
                try { t.unregister(); } catch (Exception ignored) {}
            }
        }
    }

    private void syncTeamsOn(Scoreboard board) {
        ensureTeamsExist(board);

        Map<String, String> desiredTeam = new HashMap<>();
        for (KMCTeam kmcTeam : plugin.getTeamManager().getTeamsInOrder()) {
            String btName = bukkitTeamName(kmcTeam);
            for (UUID uuid : kmcTeam.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) desiredTeam.put(p.getName(), btName);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers())
            desiredTeam.putIfAbsent(p.getName(), NO_TEAM_KEY);

        // Remove entries that moved to a different team
        for (Team bt : board.getTeams()) {
            String nm = bt.getName();
            if (!nm.equals(NO_TEAM_KEY) && !nm.matches("\\d\\d_.+")) continue;
            for (String entry : new HashSet<>(bt.getEntries())) {
                String desired = desiredTeam.get(entry);
                if (desired == null || !desired.equals(nm)) bt.removeEntry(entry);
            }
        }

        // Add entries that are not yet in the right team
        for (Map.Entry<String, String> e : desiredTeam.entrySet()) {
            Team bt = board.getTeam(e.getValue());
            if (bt != null && !bt.hasEntry(e.getKey())) bt.addEntry(e.getKey());
        }
    }

    private String bukkitTeamName(KMCTeam kmcTeam) {
        int index = plugin.getTeamManager().getTeamsInOrder().indexOf(kmcTeam);
        return String.format("%02d_%s", index, kmcTeam.getId());
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    public void onPlayerJoin(Player player) {
        if (plugin.getApi().isScoreboardOwnedByMinigame()) return;
        try { updatePlayer(player); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Scoreboard onJoin failed", e); }
    }

    public void onPlayerQuit(Player player) {
        boards.remove(player.getUniqueId());
        activeBuffer.remove(player.getUniqueId());
    }

    public void cleanup() {
        if (updateTask != null) updateTask.cancel();
        boards.clear();
        activeBuffer.clear();
    }

    public Scoreboard getMainScoreboard() { return mainScoreboard; }

    // ====================================================================
    // Static helpers (also accessible via TabListManager for callers that
    // import that class already)
    // ====================================================================

    public static NamedTextColor toNamed(ChatColor cc) {
        return COLOR_MAP.getOrDefault(cc, NamedTextColor.WHITE);
    }

    // ====================================================================
    // Sidebar content
    // ====================================================================

    private String safeTitle(Player p) {
        try { String t = gameBoard.title(p); return t != null ? t : "&6&lKMC"; }
        catch (Exception e) { return "&6&lKMC"; }
    }

    private List<String> safeLines(Player p) {
        try {
            List<String> raw = gameBoard.lines(p);
            if (raw == null) return null;
            List<String> out = new ArrayList<>(raw.size());
            for (String s : raw) out.add(MessageUtil.color(s == null ? "" : s));
            return out;
        } catch (Exception e) { return null; }
    }

    private List<String> buildLines(Player player) {
        List<String> lines = new ArrayList<>();

        boolean active  = plugin.getTournamentManager().isActive();
        int     round   = plugin.getTournamentManager().getCurrentRound();
        double  mul     = plugin.getTournamentManager().getMultiplier();

        String gameName = plugin.getGameManager().getActiveGame() != null
                ? plugin.getGameManager().getActiveGame().getDisplayName() : null;

        KMCTeam myTeam = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        PlayerData pd  = plugin.getPlayerDataManager().get(player.getUniqueId());
        if (pd == null) pd = plugin.getDatabaseManager().loadPlayer(player.getUniqueId());

        List<KMCTeam> allTeams = plugin.getTeamManager().getTeamsSortedByPoints();

        var lang = plugin.getLanguageManager();

        // Status
        lines.add(active ? lang.tr(player, "scoreboard.round", round, mul)
                         : lang.tr(player, "scoreboard.inactive"));
        if (gameName != null) lines.add(lang.tr(player, "scoreboard.game", gameName));
        lines.add("&r");

        // Top 5 teams
        if (!allTeams.isEmpty()) {
            lines.add(lang.tr(player, "scoreboard.top-teams"));
            int show = Math.min(TOP_TEAM_COUNT, allTeams.size());
            for (int i = 0; i < show; i++) {
                KMCTeam t = allTeams.get(i);
                String rank = switch (i) {
                    case 0 -> "&6#1";
                    case 1 -> "&7#2";
                    case 2 -> "&c#3";
                    default -> "&8#" + (i + 1);
                };
                lines.add(rank + " " + t.getColor() + t.getDisplayName() + " &8- &e" + t.getPoints());
            }
        }
        lines.add("&r ");

        // Player's team
        if (myTeam != null) {
            lines.add(lang.tr(player, "scoreboard.your-team"));
            lines.add(myTeam.getColor() + myTeam.getDisplayName() + " &8- &e" + myTeam.getPoints() + "p");
        }

        lines.add("&r  ");
        lines.add(lang.tr(player, "scoreboard.your-points", (pd != null ? pd.getPoints() : 0)));
        return lines;
    }

    private static final ChatColor[] UNIQUE_COLORS = ChatColor.values();

    private String uniqueEntry(String text, Set<String> taken) {
        String colored = MessageUtil.color(text);
        for (ChatColor color : UNIQUE_COLORS) {
            String entry = color + "" + ChatColor.RESET + colored;
            if (entry.length() > 40) entry = entry.substring(0, 40);
            if (!taken.contains(entry)) return entry;
        }
        return colored;
    }
}
