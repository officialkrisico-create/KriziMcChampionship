package nl.kmc.kmccore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.TabListManager;
import nl.kmc.kmccore.models.KMCTeam;
import nl.kmc.kmccore.models.PlayerData;
import nl.kmc.kmccore.models.PointAward;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.*;

/**
 * Builds the post-event signed book given to every player at the end
 * of a tournament.
 *
 * <p>Pages:
 * <ol>
 *   <li>KMC Event Number, Winning Team, Best Player</li>
 *   <li>Full Team Leaderboard</li>
 *   <li>Top 10 Players + viewer's placement</li>
 *   <li>Viewer's Team Breakdown (who got what)</li>
 *   <li>Viewer's Points Breakdown (where points came from)</li>
 *   <li>Viewer's Stats (kills, deaths, K/D, lap times etc.)</li>
 * </ol>
 */
public final class EventStatsBookBuilder {

    private EventStatsBookBuilder() {}

    public static ItemStack buildBookFor(KMCCore plugin, Player viewer, int eventNumber) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;

        meta.author(Component.text("KMCStats", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.title(Component.text("KMC #" + eventNumber + " Stats", NamedTextColor.GOLD));

        meta.addPages(
                page1Overview(plugin, eventNumber),
                page2TeamLeaderboard(plugin),
                page3TopPlayers(plugin, viewer),
                page4TeamBreakdown(plugin, viewer),
                page5PointsBreakdown(plugin, viewer),
                page6PersonalStats(plugin, viewer)
        );

        book.setItemMeta(meta);
        return book;
    }

    // ----------------------------------------------------------------
    // PAGE 1 — Event overview
    // ----------------------------------------------------------------

    private static Component page1Overview(KMCCore plugin, int eventNumber) {
        var winningTeam = plugin.getTeamManager().getTeamsSortedByPoints().stream()
                .findFirst().orElse(null);
        var bestPlayer = plugin.getPlayerDataManager().getLeaderboard().stream()
                .findFirst().orElse(null);

        var b = Component.text();
        b.append(Component.text("KMC #" + eventNumber + "\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        b.append(Component.text("━━━━━━━━━━━━━━━\n\n", NamedTextColor.GRAY));

        b.append(Component.text("Winnend Team\n", NamedTextColor.GOLD, TextDecoration.BOLD));
        if (winningTeam != null) {
            var nc = TabListManager.toNamed(winningTeam.getColor());
            b.append(Component.text(winningTeam.getDisplayName() + "\n", nc, TextDecoration.BOLD));
            b.append(Component.text(winningTeam.getPoints() + " punten\n\n", NamedTextColor.DARK_GRAY));
        } else {
            b.append(Component.text("Onbekend\n\n", NamedTextColor.DARK_GRAY));
        }

        b.append(Component.text("Beste Speler\n", NamedTextColor.GOLD, TextDecoration.BOLD));
        if (bestPlayer != null) {
            b.append(Component.text(bestPlayer.getName() + "\n", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
            b.append(Component.text(bestPlayer.getPoints() + " punten", NamedTextColor.DARK_GRAY));
        } else {
            b.append(Component.text("Onbekend", NamedTextColor.DARK_GRAY));
        }

        return b.build();
    }

    // ----------------------------------------------------------------
    // PAGE 2 — Full team leaderboard
    // ----------------------------------------------------------------

    private static Component page2TeamLeaderboard(KMCCore plugin) {
        var b = Component.text();
        b.append(Component.text("Team Klassement\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        b.append(Component.text("━━━━━━━━━━━━━━━\n", NamedTextColor.GRAY));

        var teams = plugin.getTeamManager().getTeamsSortedByPoints();
        int rank = 1;
        for (KMCTeam t : teams) {
            var nc = TabListManager.toNamed(t.getColor());
            String medal = rank == 1 ? "★ " : rank == 2 ? "2. " : rank == 3 ? "3. " : rank + ". ";
            b.append(Component.text(medal, NamedTextColor.GRAY));
            b.append(Component.text(t.getDisplayName(), nc, TextDecoration.BOLD));
            b.append(Component.text("\n  " + t.getPoints() + " pt\n", NamedTextColor.DARK_GRAY));
            rank++;
        }
        return b.build();
    }

    // ----------------------------------------------------------------
    // PAGE 3 — Top 10 players + viewer placement
    // ----------------------------------------------------------------

    private static Component page3TopPlayers(KMCCore plugin, Player viewer) {
        var leaderboard = plugin.getPlayerDataManager().getLeaderboard();

        var b = Component.text();
        b.append(Component.text("Top Spelers\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        b.append(Component.text("━━━━━━━━━━━━━━━\n", NamedTextColor.GRAY));

        for (int i = 0; i < Math.min(10, leaderboard.size()); i++) {
            PlayerData pd = leaderboard.get(i);
            boolean isViewer = pd.getUuid().equals(viewer.getUniqueId());

            var color = isViewer ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY;
            b.append(Component.text((i + 1) + ". ", NamedTextColor.GRAY));
            b.append(Component.text(pd.getName(), color,
                    isViewer ? TextDecoration.BOLD : TextDecoration.UNDERLINED.withState(false)));
            b.append(Component.text(" — " + pd.getPoints() + "\n", NamedTextColor.DARK_GRAY));
        }

        // Viewer's placement if outside top 10
        int viewerPlace = -1;
        for (int i = 0; i < leaderboard.size(); i++) {
            if (leaderboard.get(i).getUuid().equals(viewer.getUniqueId())) {
                viewerPlace = i + 1; break;
            }
        }
        if (viewerPlace > 10) {
            b.append(Component.text("\nJij: #" + viewerPlace + "\n", NamedTextColor.GOLD, TextDecoration.BOLD));
            PlayerData me = plugin.getPlayerDataManager().get(viewer.getUniqueId());
            if (me != null) {
                b.append(Component.text(me.getPoints() + " punten", NamedTextColor.DARK_GRAY));
            }
        } else if (viewerPlace > 0) {
            b.append(Component.text("\nJouw plaats: #" + viewerPlace, NamedTextColor.DARK_GRAY));
        }

        return b.build();
    }

    // ----------------------------------------------------------------
    // PAGE 4 — Viewer's team — who got what
    // ----------------------------------------------------------------

    private static Component page4TeamBreakdown(KMCCore plugin, Player viewer) {
        var b = Component.text();
        KMCTeam team = plugin.getTeamManager().getTeamByPlayer(viewer.getUniqueId());

        if (team == null) {
            b.append(Component.text("Geen team\n\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            b.append(Component.text("Je was niet bij een team in dit toernooi.",
                    NamedTextColor.DARK_GRAY));
            return b.build();
        }

        var nc = TabListManager.toNamed(team.getColor());
        b.append(Component.text(team.getDisplayName() + "\n", nc, TextDecoration.BOLD));
        b.append(Component.text("━━━━━━━━━━━━━━━\n", NamedTextColor.GRAY));
        b.append(Component.text("Punten per lid:\n\n", NamedTextColor.DARK_GRAY));

        // Sort members by points desc
        List<PlayerData> members = new ArrayList<>();
        for (UUID uuid : team.getMembers()) {
            PlayerData pd = plugin.getPlayerDataManager().get(uuid);
            if (pd == null) {
                pd = plugin.getDatabaseManager().loadPlayer(uuid);
            }
            if (pd != null) members.add(pd);
        }
        members.sort((a, x) -> Integer.compare(x.getPoints(), a.getPoints()));

        for (PlayerData pd : members) {
            boolean isViewer = pd.getUuid().equals(viewer.getUniqueId());
            b.append(Component.text(pd.getName(),
                    isViewer ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY,
                    isViewer ? TextDecoration.BOLD : TextDecoration.UNDERLINED.withState(false)));
            b.append(Component.text(" — " + pd.getPoints() + "\n", NamedTextColor.DARK_GRAY));
        }

        b.append(Component.text("\nTotaal: " + team.getPoints(), NamedTextColor.DARK_RED, TextDecoration.BOLD));

        return b.build();
    }

    // ----------------------------------------------------------------
    // PAGE 5 — Viewer's points breakdown by reason
    // ----------------------------------------------------------------

    private static Component page5PointsBreakdown(KMCCore plugin, Player viewer) {
        var b = Component.text();
        b.append(Component.text("Jouw Punten\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        b.append(Component.text("━━━━━━━━━━━━━━━\n", NamedTextColor.GRAY));

        var awards = plugin.getDatabaseManager().loadAwardsForPlayer(viewer.getUniqueId());
        if (awards.isEmpty()) {
            b.append(Component.text("Geen punten dit toernooi.", NamedTextColor.DARK_GRAY));
            return b.build();
        }

        // Group by displayReason → sum
        Map<String, Integer> byReason = new LinkedHashMap<>();
        int total = 0;
        for (PointAward a : awards) {
            byReason.merge(a.getDisplayReason(), a.getAmount(), Integer::sum);
            total += a.getAmount();
        }

        // Sort by amount desc
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byReason.entrySet());
        sorted.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));

        for (var e : sorted) {
            b.append(Component.text(e.getKey(), NamedTextColor.DARK_GRAY));
            b.append(Component.text(": +" + e.getValue() + "\n", NamedTextColor.DARK_RED));
        }

        b.append(Component.text("\nTotaal: " + total, NamedTextColor.DARK_RED, TextDecoration.BOLD));
        return b.build();
    }

    // ----------------------------------------------------------------
    // PAGE 6 — Personal stats
    // ----------------------------------------------------------------

    private static Component page6PersonalStats(KMCCore plugin, Player viewer) {
        var b = Component.text();
        b.append(Component.text("Jouw Stats\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        b.append(Component.text("━━━━━━━━━━━━━━━\n\n", NamedTextColor.GRAY));

        PlayerData pd = plugin.getPlayerDataManager().get(viewer.getUniqueId());
        if (pd == null) pd = plugin.getDatabaseManager().loadPlayer(viewer.getUniqueId());

        if (pd == null) {
            b.append(Component.text("Geen data beschikbaar.", NamedTextColor.DARK_GRAY));
            return b.build();
        }

        line(b, "Punten",       String.valueOf(pd.getPoints()));
        line(b, "Kills",        String.valueOf(pd.getKills()));
        line(b, "Deaths",       String.valueOf(pd.getDeaths()));
        if (pd.getDeaths() > 0) {
            double kd = (double) pd.getKills() / pd.getDeaths();
            line(b, "K/D",      String.format("%.2f", kd));
        }
        line(b, "Wins",         String.valueOf(pd.getWins()));
        line(b, "Games",        String.valueOf(pd.getGamesPlayed()));
        line(b, "Win streak",   pd.getWinStreak() + " (best: " + pd.getBestWinStreak() + ")");

        if (pd.getFavouriteGame() != null) {
            line(b, "Favo game", pd.getFavouriteGame().replace("_", " "));
        }

        return b.build();
    }

    private static void line(net.kyori.adventure.text.TextComponent.Builder b, String label, String value) {
        b.append(Component.text(label + ": ", NamedTextColor.DARK_GRAY));
        b.append(Component.text(value + "\n", NamedTextColor.DARK_RED, TextDecoration.BOLD));
    }
}
