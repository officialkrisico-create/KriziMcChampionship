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
import java.util.logging.Level;

/**
 * Manages team-coloured names in chat, tab list, and above-head.
 *
 * <p><b>Tab list sorting:</b> Minecraft sorts the tab list alphabetically
 * by internal Bukkit Team name. To group teammates together in the order
 * they're defined, each internal team name gets a 2-digit numeric prefix:
 *
 * <pre>
 *   00_rode_ratten       ← first team in config = appears first in tab
 *   01_oranje_otters
 *   02_gele_gnoes
 *   ...
 *   zz_nobody            ← prefix for teamless players (sort last)
 * </pre>
 *
 * <p><b>Tab list always smooth:</b> syncAllBoards() is idempotent and
 * wrapped in try/catch at each step — no exceptions bubble up to break
 * refresh loops.
 */
public class TabListManager {

    private final KMCCore plugin;
    private final Scoreboard mainScoreboard;

    /** Per-player personal boards. */
    private final Map<UUID, Scoreboard> personalBoards = new HashMap<>();

    /** Bukkit team name for teamless players — sorts last. */
    private static final String NO_TEAM_KEY = "zz_noteam";

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
        ensureTeamsExist(mainScoreboard);
    }

    // ================================================================
    // Public API
    // ================================================================

    public void registerPersonalBoard(UUID uuid, Scoreboard board) {
        personalBoards.put(uuid, board);
        try {
            ensureTeamsExist(board);
            syncTeamsOn(board);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "registerPersonalBoard failed", e);
        }
    }

    public void unregisterPersonalBoard(UUID uuid) {
        personalBoards.remove(uuid);
    }

    public Scoreboard getMainScoreboard() { return mainScoreboard; }

    /**
     * Walks every registered personal board + the main board, re-aligning
     * team memberships to match reality. Safe to call any number of times.
     */
    public void syncAllBoards() {
        for (Scoreboard board : new ArrayList<>(personalBoards.values())) {
            try { syncTeamsOn(board); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "syncTeamsOn failed", e); }
        }
        try { syncTeamsOn(mainScoreboard); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "syncTeamsOn(main) failed", e); }
    }

    /** Legacy alias. */
    public void refreshAllNametags() { syncAllBoards(); }

    public void refreshAll() {
        syncAllBoards();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { updateTabList(p); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "updateTabList failed for " + p.getName(), e); }
        }
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
    // Chat message builders
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
    // Internal team management
    // ----------------------------------------------------------------

    /**
     * Builds the internal Bukkit team name for a given KMC team.
     * Uses a numeric prefix so tab list sorts teams in their config order.
     */
    private String bukkitTeamName(KMCTeam kmcTeam) {
        int index = plugin.getTeamManager().getTeamsInOrder().indexOf(kmcTeam);
        return String.format("%02d_%s", index, kmcTeam.getId());
    }

    /** Internal name for teamless players. Sorts last. */
    private String bukkitNoTeamName() { return NO_TEAM_KEY; }

    /** Ensures a Bukkit Team exists on the board for each KMC team + a "no team" group. */
    private void ensureTeamsExist(Scoreboard board) {
        // KMC teams
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

        // No-team group — for teamless players, no prefix, default color
        Team none = board.getTeam(bukkitNoTeamName());
        if (none == null) {
            none = board.registerNewTeam(bukkitNoTeamName());
            none.prefix(Component.empty());
            none.color(NamedTextColor.GRAY);
        }

        // Clean up any orphaned kmc_* teams from older versions
        for (Team t : new ArrayList<>(board.getTeams())) {
            String nm = t.getName();
            if (nm.startsWith("kmc_")) {
                try { t.unregister(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Aligns the Bukkit team memberships on a given scoreboard to match
     * the current team assignments. Called by syncAllBoards().
     */
    private void syncTeamsOn(Scoreboard board) {
        ensureTeamsExist(board);

        // Build player → desired team name
        Map<String, String> desiredTeam = new HashMap<>();
        for (KMCTeam kmcTeam : plugin.getTeamManager().getTeamsInOrder()) {
            String btName = bukkitTeamName(kmcTeam);
            for (UUID uuid : kmcTeam.getMembers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) desiredTeam.put(p.getName(), btName);
            }
        }
        // Everyone else → no-team
        for (Player p : Bukkit.getOnlinePlayers()) {
            desiredTeam.putIfAbsent(p.getName(), bukkitNoTeamName());
        }

        // Clear all KMC + no-team entries, then add the desired ones
        for (Team bt : board.getTeams()) {
            String nm = bt.getName();
            if (!nm.equals(NO_TEAM_KEY) && !nm.matches("\\d\\d_.+")) continue;

            Set<String> current = new HashSet<>(bt.getEntries());
            for (String entry : current) {
                String desired = desiredTeam.get(entry);
                if (desired == null || !desired.equals(nm)) {
                    bt.removeEntry(entry);
                }
            }
        }

        // Add desired entries
        for (Map.Entry<String, String> e : desiredTeam.entrySet()) {
            Team bt = board.getTeam(e.getValue());
            if (bt != null && !bt.hasEntry(e.getKey())) {
                bt.addEntry(e.getKey());
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
