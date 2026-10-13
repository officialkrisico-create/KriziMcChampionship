package nl.kmc.kmccore.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.scoreboard.ScoreboardManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import nl.kmc.kmccore.presentation.ChatLayout;
import nl.kmc.kmccore.scoring.PointsLedger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tab-list header/footer and in-game chat component builders.
 *
 * <p>Board ownership and team-prefix sync have moved to
 * {@link ScoreboardManager}, which now holds the single authoritative
 * {@code Map<UUID, Scoreboard>}. This class is retained for its chat-builder
 * methods ({@link #buildChatMessage}, {@link #buildTeamChatMessage}) and for
 * static helpers ({@link #fromLegacy}, {@link #toNamed}) that are referenced
 * across the codebase.
 */
public class TabListManager {

    private final KMCCore plugin;

    // Color map is defined in ScoreboardManager (single source) — delegated via toNamed().
    // Kept here as a static alias so existing call-sites (EventStatsBookBuilder etc.)
    // do not need to change their import.

    public TabListManager(KMCCore plugin) {
        this.plugin = plugin;
    }

    // ================================================================
    // Tab-list header / footer
    // ================================================================

    public void updateTabList(Player player) {
        // Team-coloured list name so the (team-grouped) tab reads like MCC.
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        String colour = team != null ? team.getColor().toString() : "&7";
        player.playerListName(fromLegacy(colour + player.getName()));

        if (mccLayout()) {
            player.sendPlayerListHeaderAndFooter(fromLegacy(mccHeader(player)), fromLegacy(mccFooter(player)));
            hideVanillaEntries();
            return;
        }
        player.sendPlayerListHeaderAndFooter(fromLegacy(buildHeader(player)), fromLegacy(buildFooter(player)));
    }

    // ── MCC layout: title, "<game> - PUNTEN", then every team on one line with its players below ──

    /** Pixel width every tab line is padded to, so the list reads as a clean table. */
    private static final int TAB_W = 300;

    private boolean mccLayout() {
        return plugin.getConfig().getString("tablist.layout", "mcc").equalsIgnoreCase("mcc");
    }

    /**
     * The client centres every header/footer line by itself, so a line is not padded here: padding on
     * one side only would push the text to the right of the table.
     */
    private static String centred(String s) {
        return s;
    }

    /**
     * {@code left} flush left, {@code right} flush right, padded to the table width. The padding is
     * reset first: a bold team name would otherwise make every space of the gap one pixel wider and
     * push that row out of line with the others.
     */
    static String leftRight(String left, String right) {
        int gap = TAB_W - ChatLayout.px(left) - ChatLayout.px(right);
        return left + "§r" + ChatLayout.spaces(gap) + right;
    }

    /** Max lines of player names under a team; the rest becomes "+N" (the tab has to fit on small GUI scales). */
    private static final int MAX_NAME_LINES = 2;

    /**
     * Player names as centred lines (the client centres), separated by a dot, wrapped when a line would be
     * wider than the table, and cut to {@link #MAX_NAME_LINES} lines with a "+N" for the rest.
     */
    static List<String> nameLines(List<String> names) {
        List<String> lines = new ArrayList<>();
        if (names.isEmpty()) return lines;
        final String sep = "  §8•  ";
        final int limit = TAB_W - 20;
        List<List<String>> groups = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String n : names) {
            List<String> trial = new ArrayList<>(cur);
            trial.add(n);
            if (!cur.isEmpty() && ChatLayout.px(String.join(sep, trial)) > limit) {
                groups.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(n);
        }
        groups.add(cur);
        int hidden = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (i < MAX_NAME_LINES) lines.add(String.join(sep, groups.get(i)));
            else hidden += groups.get(i).size();
        }
        if (hidden > 0) {
            int last = lines.size() - 1;
            lines.set(last, lines.get(last) + "  §8+" + hidden);
        }
        return lines;
    }

    /**
     * Takes the vanilla player entries (head, name, ping) out of everybody's tab: the MCC layout lives in the
     * header, so a leftover entry (your own name below the table) only gets in the way.
     */
    private void hideVanillaEntries() {
        if (!mccLayout() || !plugin.getConfig().getBoolean("tablist.hide-players", true)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                try {
                    if (viewer.isListed(other)) viewer.unlistPlayer(other);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.FINE, "unlist failed for " + other.getName(), e);
                }
            }
        }
    }

    /** Team rows with the points of the running game (live), or the tournament totals outside a game. */
    private List<String> mccTeamRows(Player viewer, boolean inGame) {
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        Map<String, Integer> gamePts = new HashMap<>();
        if (inGame) {
            var ledger = plugin.getPointsLedger();
            gamePts.putAll(PointsLedger.byTeam(ledger.forGame(ledger.lastGameSeq())));
        }
        List<KMCTeam> ordered = new ArrayList<>(teams);
        if (inGame) ordered.sort((a, b) -> Integer.compare(gamePts.getOrDefault(b.getId(), 0), gamePts.getOrDefault(a.getId(), 0)));
        String icon = plugin.getConfig().getString("tablist.points-icon", "●");
        KMCTeam mine = plugin.getTeamManager().getTeamByPlayer(viewer.getUniqueId());
        List<String> rows = new ArrayList<>();
        int rank = 1;
        for (KMCTeam t : ordered) {
            if (rank > 8) break;
            int pts = inGame ? gamePts.getOrDefault(t.getId(), 0) : t.getPoints();
            String c = t.getColor().toString();
            boolean me = mine != null && mine.getId().equals(t.getId());
            // team name centred and bold; the client centres the line itself
            rows.add((me ? "§e➤ " : "") + "§f" + rank + ". " + c + "◆ §l" + t.getDisplayName()
                    + " §8— §e" + pts + " §6" + icon);
            List<String> names = new ArrayList<>();
            for (UUID u : t.getMembers()) {
                Player online = Bukkit.getPlayer(u);
                String n = online != null ? online.getName() : Bukkit.getOfflinePlayer(u).getName();
                if (n == null) continue;
                names.add((online != null ? c : "§8") + n);
            }
            rows.addAll(nameLines(names));
            rank++;
        }
        return rows;
    }

    private String mccHeader(Player viewer) {
        var lang = plugin.getLanguageManager();
        String name = plugin.getConfig().getString("tournament.name", "Krizi Minecraft Championship");
        String presented = plugin.getConfig().getString("tablist.presented-by", "");
        var active = plugin.getGameManager().getActiveGame();
        boolean inGame = active != null;

        StringBuilder sb = new StringBuilder("\n");
        sb.append(centred("§6§l" + name.toUpperCase())).append("\n");
        if (!presented.isBlank()) sb.append(centred("§e" + presented)).append("\n");
        sb.append("§c§m").append(ChatLayout.spaces(TAB_W)).append("\n");
        String title = inGame
                ? "§f§l" + ChatColor.stripColor(active.getDisplayName()) + " §7— " + lang.tr(viewer, "tablist.game-points")
                : lang.tr(viewer, "tablist.standings-title");
        sb.append(centred(title)).append("\n\n");
        for (String row : mccTeamRows(viewer, inGame)) sb.append(row).append("\n");
        return sb.toString().replace('§', '&');
    }

    private String mccFooter(Player viewer) {
        var lang = plugin.getLanguageManager();
        int round = plugin.getTournamentManager().getCurrentRound();
        int total = plugin.getTournamentManager().getTotalRounds();
        double mul = plugin.getTournamentManager().getMultiplier();
        String line = lang.tr(viewer, "tablist.round-line", round, total, fmtMul(mul));
        return ("\n" + centred(line) + "\n" + centred(lang.tr(viewer, "tablist.vote-hint")) + "\n").replace('§', '&');
    }

    private static String fmtMul(double m) {
        return m == Math.rint(m) ? String.valueOf((int) m) : String.format(java.util.Locale.ROOT, "%.2f", m);
    }

    /**
     * Refreshes the tab header/footer of everybody, also while a game owns the scoreboard — the MCC
     * layout shows the live points of the running game, so it has to keep moving.
     */
    public void refreshLive() {
        if (!mccLayout()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { p.sendPlayerListHeaderAndFooter(fromLegacy(mccHeader(p)), fromLegacy(mccFooter(p))); }
            catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "tab refresh failed for " + p.getName(), e);
            }
        }
        hideVanillaEntries();
    }

    /** MCC-style header: championship title + round + active game (in viewer's language). */
    private String buildHeader(Player viewer) {
        var lang     = plugin.getLanguageManager();
        String name  = plugin.getConfig().getString("tournament.name", "Krizi Minecraft Championship");
        int round    = plugin.getTournamentManager().getCurrentRound();
        int total    = plugin.getConfig().getInt("tournament.total-rounds", 8);
        boolean live = plugin.getTournamentManager().isActive();

        String gameLine;
        var active = plugin.getGameManager().getActiveGame();
        if (active != null) {
            gameLine = lang.tr(viewer, "tablist.game", active.getDisplayName(), round, total);
        } else if (live) {
            gameLine = lang.tr(viewer, "tablist.intermission", round, total);
        } else {
            gameLine = lang.tr(viewer, "tablist.lobby");
        }
        return "\n &6&l" + name + "\n " + gameLine + "\n";
    }

    /** MCC-style footer: live team standings with points (in viewer's language). */
    private String buildFooter(Player viewer) {
        var lang = plugin.getLanguageManager();
        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        StringBuilder sb = new StringBuilder("\n ").append(lang.tr(viewer, "tablist.standings")).append("\n ");
        String[] medals = {"&6①", "&7②", "&c③"};
        if (teams.isEmpty()) {
            sb.append(lang.tr(viewer, "tablist.no-points"));
        } else {
            for (int i = 0; i < Math.min(3, teams.size()); i++) {
                KMCTeam t = teams.get(i);
                if (i > 0) sb.append("  ");
                sb.append(medals[i]).append(' ').append(t.getColor().toString())
                  .append(t.getDisplayName()).append(" &f").append(t.getPoints());
            }
        }
        sb.append("\n ").append(lang.tr(viewer, "tablist.vote-hint")).append("\n");
        return sb.toString();
    }

    /**
     * Syncs team nametags on all personal boards (via {@link ScoreboardManager})
     * and refreshes the tab header/footer for every online player.
     *
     * <p>No-ops for the tab header while a minigame owns the scoreboard, but
     * team-prefix sync always runs (it is safe during minigames).
     */
    public void refreshAll() {
        // Team-prefix sync is always safe
        plugin.getScoreboardManager().syncAllBoards();

        if (plugin.getApi().isScoreboardOwnedByMinigame()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { updateTabList(p); }
            catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "updateTabList failed for " + p.getName(), e);
            }
        }
    }

    /** Alias kept for call-sites that only need nametag refresh, not tab header. */
    public void refreshAllNametags() {
        plugin.getScoreboardManager().syncAllBoards();
    }

    public Scoreboard getMainScoreboard() {
        return plugin.getScoreboardManager().getMainScoreboard();
    }

    // ================================================================
    // Chat component builders
    // ================================================================

    public Component buildChatMessage(Player player, String message) {
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        NamedTextColor nameColor = NamedTextColor.WHITE;

        var builder = Component.text().color(NamedTextColor.WHITE);
        if (team != null) {
            NamedTextColor tc = toNamed(team.getColor());
            nameColor = tc;
            builder.append(Component.text("[",                   NamedTextColor.GRAY))
                   .append(Component.text(team.getDisplayName(), tc, TextDecoration.BOLD))
                   .append(Component.text("] ",                  NamedTextColor.GRAY));
        }
        builder.append(Component.text(player.getName(), nameColor, TextDecoration.BOLD))
               .append(Component.text(": ",  NamedTextColor.GRAY))
               .append(Component.text(message, NamedTextColor.WHITE));
        return builder.build();
    }

    public Component buildTeamChatMessage(Player player, KMCTeam team, String message) {
        NamedTextColor tc = toNamed(team.getColor());
        return Component.text()
                .append(Component.text("[TC] ",                 NamedTextColor.GRAY))
                .append(Component.text("[" + team.getDisplayName() + "] ", tc, TextDecoration.BOLD))
                .append(Component.text(player.getName(),        tc))
                .append(Component.text(": ",                    NamedTextColor.GRAY))
                .append(Component.text(message,                 NamedTextColor.WHITE))
                .build();
    }

    // ================================================================
    // Static helpers (widely imported — keep here for backward compat)
    // ================================================================

    public static Component fromLegacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(text);
    }

    /** Delegates to {@link ScoreboardManager#toNamed(ChatColor)}. */
    public static NamedTextColor toNamed(ChatColor cc) {
        return ScoreboardManager.toNamed(cc);
    }
}
