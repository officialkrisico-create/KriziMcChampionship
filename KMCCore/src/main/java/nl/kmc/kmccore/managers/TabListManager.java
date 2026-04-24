package nl.kmc.kmccore.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCTeam;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/**
 * Manages team-coloured names in chat, tab list, and above-head.
 *
 * <p><b>FIX for "only own name is coloured":</b>
 *
 * <p>Each player has their own personal Scoreboard so their sidebar can
 * show per-player data. A team's "prefix" (the [TeamName] tag + colour)
 * lives on a Bukkit {@link Team} object <em>inside</em> that personal
 * scoreboard. If player A's board has only A registered on "kmc_red"
 * and not B, then A sees B's name uncoloured.
 *
 * <p>The cure: {@link #syncAllBoards()} walks every online player's
 * personal scoreboard and ensures every KMC team contains every correct
 * player entry. We call this whenever:
 * <ul>
 *   <li>A player joins or quits (via PlayerJoinQuitListener)</li>
 *   <li>A player is added to or removed from a team</li>
 *   <li>A new personal sidebar is first created</li>
 *   <li>Tournament reset / hardreset</li>
 * </ul>
 */
public class TabListManager {

    private final KMCCore plugin;

    /** Kept as a reference — but personal boards do the real work. */
    private final Scoreboard mainScoreboard;

    /** Per-player boards registered by ScoreboardManager. Shared so we can walk them. */
    private final Map<UUID, Scoreboard> personalBoards = new HashMap<>();

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

    public TabListManager(KMCCore plugin) {
        this.plugin = plugin;
        this.mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        // Set up teams on main scoreboard (used as a template)
        ensureTeamsExist(mainScoreboard);
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * Registers a personal scoreboard so it gets team-sync treatment.
     * Called by ScoreboardManager when it creates a new sidebar.
     */
    public void registerPersonalBoard(UUID uuid, Scoreboard board) {
        personalBoards.put(uuid, board);
        ensureTeamsExist(board);
        syncTeamsOn(board);
    }

    /** Removes personal board (player quit). */
    public void unregisterPersonalBoard(UUID uuid) {
        personalBoards.remove(uuid);
    }

    public Scoreboard getMainScoreboard() { return mainScoreboard; }

    /**
     * THE CRITICAL METHOD: walks every registered personal scoreboard
     * and syncs KMC team memberships so all players see all prefixes.
     */
    public void syncAllBoards() {
        for (Scoreboard board : personalBoards.values()) syncTeamsOn(board);
        // Also keep main board in sync for completeness
        syncTeamsOn(mainScoreboard);
    }

    /** Legacy alias. */
    public void refreshAllNametags() { syncAllBoards(); }

    /** Refresh tab list header/footer for every player + re-sync team prefixes. */
    public void refreshAll() {
        syncAllBoards();
        for (Player p : Bukkit.getOnlinePlayers()) updateTabList(p);
    }

    // ----------------------------------------------------------------
    // Tab list header/footer
    // ----------------------------------------------------------------

    public void updateTabList(Player player) {
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());

        String rawHeader = plugin.getConfig().getString("tablist.header", "\n&6&lKMC Tournament\n")
                .replace("{round}", String.valueOf(plugin.getTournamentManager().getCurrentRound()))
                .replace("{multiplier}", String.valueOf(plugin.getTournamentManager().getMultiplier()));

        String rawFooter = plugin.getConfig().getString("tablist.footer", "\n&7Team: {team_color}{team_name}\n")
                .replace("{team_color}", team != null ? team.getColor().toString() : "&8")
                .replace("{team_name}",  team != null ? team.getDisplayName() : "Geen");

        player.sendPlayerListHeaderAndFooter(fromLegacy(rawHeader), fromLegacy(rawFooter));
    }

    // ----------------------------------------------------------------
    // Chat message builders (unchanged)
    // ----------------------------------------------------------------

    public Component buildChatMessage(Player player, String message) {
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(player.getUniqueId());
        NamedTextColor nameColor = NamedTextColor.WHITE;

        net.kyori.adventure.text.TextComponent.Builder builder =
                Component.text().color(NamedTextColor.WHITE);

        if (team != null) {
            NamedTextColor tc = COLOR_MAP.getOrDefault(team.getColor(), NamedTextColor.WHITE);
            nameColor = tc;
            builder.append(Component.text("[", NamedTextColor.GRAY))
                   .append(Component.text(team.getDisplayName(), tc, TextDecoration.BOLD))
                   .append(Component.text("] ", NamedTextColor.GRAY));
        }
        builder.append(Component.text(player.getName(), nameColor, TextDecoration.BOLD))
               .append(Component.text(": ", NamedTextColor.GRAY))
               .append(Component.text(message, NamedTextColor.WHITE));
        return builder.build();
    }

    public Component buildTeamChatMessage(Player player, KMCTeam team, String message) {
        NamedTextColor tc = COLOR_MAP.getOrDefault(team.getColor(), NamedTextColor.WHITE);
        return Component.text()
                .append(Component.text("[TC] ", NamedTextColor.GRAY))
                .append(Component.text("[" + team.getDisplayName() + "] ", tc, TextDecoration.BOLD))
                .append(Component.text(player.getName(), tc))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(message, NamedTextColor.WHITE))
                .build();
    }

    // ----------------------------------------------------------------
    // Team sync internals
    // ----------------------------------------------------------------

    /** Makes sure every KMC team exists on the given scoreboard with correct prefix. */
    private void ensureTeamsExist(Scoreboard board) {
        for (KMCTeam kmcTeam : plugin.getTeamManager().getAllTeams()) {
            String teamKey = "kmc_" + kmcTeam.getId();
            Team bt = board.getTeam(teamKey);
            if (bt == null) {
                bt = board.registerNewTeam(teamKey);
            }
            NamedTextColor ntc = COLOR_MAP.getOrDefault(kmcTeam.getColor(), NamedTextColor.WHITE);
            bt.prefix(Component.text("[" + kmcTeam.getDisplayName() + "] ", ntc, TextDecoration.BOLD));
            bt.color(ntc);
            bt.setAllowFriendlyFire(false);
            bt.setCanSeeFriendlyInvisibles(true);
        }
    }

    /** Aligns team memberships on one scoreboard to match actual team assignments. */
    private void syncTeamsOn(Scoreboard board) {
        ensureTeamsExist(board);

        for (KMCTeam kmcTeam : plugin.getTeamManager().getAllTeams()) {
            Team bt = board.getTeam("kmc_" + kmcTeam.getId());
            if (bt == null) continue;

            // Build desired member name set
            Set<String> desired = new HashSet<>();
            for (UUID uuid : kmcTeam.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) desired.add(p.getName());
            }

            // Remove any entries not in desired
            Set<String> current = new HashSet<>(bt.getEntries());
            for (String entry : current) {
                if (!desired.contains(entry)) bt.removeEntry(entry);
            }

            // Add any missing entries
            for (String name : desired) {
                if (!bt.hasEntry(name)) bt.addEntry(name);
            }
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    public static Component fromLegacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(text);
    }

    public static NamedTextColor toNamed(ChatColor cc) {
        return COLOR_MAP.getOrDefault(cc, NamedTextColor.WHITE);
    }
}
