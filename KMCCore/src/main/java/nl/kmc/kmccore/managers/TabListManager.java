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
 * <p><b>Ownership-aware:</b> {@link #refreshAll()} no-ops while a
 * minigame holds the scoreboard lock. The minigame is responsible for
 * keeping team colours visible during its lifetime; the alternative
 * (running both refresh loops at once) caused the flicker you saw.
 *
 * <p>Note: {@link #syncAllBoards()} still runs because it only touches
 * Bukkit Team membership (which IS shared across all boards), not the
 * sidebar objective. So team prefixes stay consistent everywhere even
 * during a minigame.
 */
public class TabListManager {

    private final KMCCore plugin;
    private final Scoreboard mainScoreboard;
    private final Map<UUID, Scoreboard> personalBoards = new HashMap<>();
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

    /** Always-safe: only touches Bukkit Team membership (shared across boards). */
    public void syncAllBoards() {
        for (Scoreboard board : new ArrayList<>(personalBoards.values())) {
            try { syncTeamsOn(board); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "syncTeamsOn failed", e); }
        }
        try { syncTeamsOn(mainScoreboard); }
        catch (Exception e) { plugin.getLogger().log(Level.WARNING, "syncTeamsOn(main) failed", e); }
    }

    public void refreshAllNametags() { syncAllBoards(); }

    /**
     * Full refresh — sync teams AND update the tab header/footer.
     * No-op while a minigame owns the scoreboard, except for team sync
     * (which is always safe since it only touches shared Team objects).
     */
    public void refreshAll() {
        // Always sync team prefixes — they're shared across all boards
        syncAllBoards();

        // But only touch the tab header/footer when nobody else owns it
        if (plugin.getApi().isScoreboardOwnedByMinigame()) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            try { updateTabList(p); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "updateTabList failed for " + p.getName(), e); }
        }
    }

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
    // Chat builders
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

    private String bukkitTeamName(KMCTeam kmcTeam) {
        int index = plugin.getTeamManager().getTeamsInOrder().indexOf(kmcTeam);
        return String.format("%02d_%s", index, kmcTeam.getId());
    }

    private String bukkitNoTeamName() { return NO_TEAM_KEY; }

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

        Team none = board.getTeam(bukkitNoTeamName());
        if (none == null) {
            none = board.registerNewTeam(bukkitNoTeamName());
            none.prefix(Component.empty());
            none.color(NamedTextColor.GRAY);
        }

        for (Team t : new ArrayList<>(board.getTeams())) {
            String nm = t.getName();
            if (nm.startsWith("kmc_")) {
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            desiredTeam.putIfAbsent(p.getName(), bukkitNoTeamName());
        }

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

        for (Map.Entry<String, String> e : desiredTeam.entrySet()) {
            Team bt = board.getTeam(e.getValue());
            if (bt != null && !bt.hasEntry(e.getKey())) {
                bt.addEntry(e.getKey());
            }
        }
    }

    // ----------------------------------------------------------------

    public static Component fromLegacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(text);
    }

    public static NamedTextColor toNamed(ChatColor cc) {
        return COLOR_MAP.getOrDefault(cc, NamedTextColor.WHITE);
    }
}
