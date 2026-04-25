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
 * <p><b>Minigame ownership:</b> When a minigame (like Adventure Escape)
 * takes over a player's scoreboard, it calls
 * {@link nl.kmc.kmccore.api.KMCApi#acquireScoreboard(String)}. While
 * any minigame holds the lock, this manager stops overwriting player
 * scoreboards — no more flicker between the lobby sidebar and the
 * race sidebar.
 *
 * <p>When the minigame ends, it calls {@code releaseScoreboard()} and
 * the lobby sidebar comes back automatically on the next tick.
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
            updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 40L, interval);
        }
    }

    private void tickAll() {
        // CRITICAL: if a minigame owns the scoreboard, do NOTHING.
        // This prevents the flicker between the lobby sidebar and minigame sidebar.
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

    /** Force an immediate refresh for one player, ignoring ownership. */
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

        Objective existing = board.getObjective("kmc_sidebar");
        if (existing != null) {
            try { existing.unregister(); } catch (Exception ignored) {}
        }

        Objective sidebar = board.registerNewObjective("kmc_sidebar", Criteria.DUMMY,
                MessageUtil.color(plugin.getConfig().getString("scoreboard.title", "&6&lKMC")));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = buildLines(player);
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

        if (active) {
            lines.add("&7Ronde: &e" + round + " &8(&e×" + mul + "&8)");
        } else {
            lines.add("&7Status: &cInactief");
        }
        if (gameName != null) lines.add("&7Game: &b" + gameName);

        lines.add("&r");

        int reserved = 3 + 2;
        int available = MAX_SIDEBAR_LINES - lines.size() - reserved;
        int teamLinesToShow = Math.min(allTeams.size() + 1, Math.max(1, available));
        if (teamLinesToShow > 1) {
            lines.add("&6&lTop Teams:");
            int teamsLeft = teamLinesToShow - 1;
            for (int i = 0; i < Math.min(teamsLeft, allTeams.size()); i++) {
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

        if (myTeam != null) {
            lines.add("&e&lJouw Team:");
            lines.add(myTeam.getColor() + myTeam.getDisplayName()
                    + " &8- &e" + myTeam.getPoints() + " pt");
        }

        lines.add("&r  ");
        lines.add("&b&lJij:");
        lines.add("&7Punten: &e" + (pd != null ? pd.getPoints() : 0));

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
        plugin.getTabListManager().unregisterPersonalBoard(player.getUniqueId());
    }

    public void cleanup() {
        if (updateTask != null) updateTask.cancel();
        boards.clear();
    }
}
