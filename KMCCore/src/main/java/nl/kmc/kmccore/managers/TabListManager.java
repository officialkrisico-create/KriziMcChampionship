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
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());

        String rawHeader = plugin.getConfig().getString("tablist.header", "\n&6&lKMC Tournament\n")
                .replace("{round}",      String.valueOf(plugin.getTournamentManager().getCurrentRound()))
                .replace("{multiplier}", String.valueOf(plugin.getTournamentManager().getMultiplier()));

        String rawFooter = plugin.getConfig().getString("tablist.footer", "\n&7Team: {team_color}{team_name}\n")
                .replace("{team_color}", team != null ? team.getColor().toString() : "&8")
                .replace("{team_name}",  team != null ? team.getDisplayName()      : "Geen");

        player.sendPlayerListHeaderAndFooter(fromLegacy(rawHeader), fromLegacy(rawFooter));
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
