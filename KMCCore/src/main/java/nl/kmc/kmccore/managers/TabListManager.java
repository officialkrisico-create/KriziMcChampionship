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

        player.sendPlayerListHeaderAndFooter(fromLegacy(buildHeader(player)), fromLegacy(buildFooter(player)));
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
