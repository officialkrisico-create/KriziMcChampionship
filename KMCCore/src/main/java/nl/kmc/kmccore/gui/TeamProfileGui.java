package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.scoring.PointsLedger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Team profile for the lobby: emblem, rank and points, wins, the team's best game, its top scorer this event
 * and every member as a head. Click another team in the strip at the bottom to flip to its profile.
 */
public final class TeamProfileGui extends Gui {

    private final KMCCore plugin;

    public TeamProfileGui(KMCCore plugin, Player viewer, KMCTeam team) {
        super("&6&lTeamprofiel &8— " + team.getColor() + team.getDisplayName(), 5);
        this.plugin = plugin;
        render(viewer, team);
    }

    private void render(Player viewer, KMCTeam team) {
        var lm = plugin.getLanguageManager();
        List<KMCTeam> ranking = plugin.getTeamManager().getTeamsSortedByPoints();
        int rank = ranking.indexOf(team) + 1;

        ItemStack banner = plugin.getTeamEmblems().banner(team);
        ItemMeta bm = banner.getItemMeta();
        bm.setLore(List.of("§7" + lm.tr(viewer, "teaminfo.members", team.getMembers().size())));
        banner.setItemMeta(bm);
        set(4, banner);

        set(10, item(Material.GOLD_INGOT, "&e&l" + lm.tr(viewer, "teaminfo.points"),
                "&f" + team.getPoints() + " &7" + lm.tr(viewer, "teaminfo.pts")));
        set(11, item(Material.NETHER_STAR, "&6&l" + lm.tr(viewer, "teaminfo.rank"),
                "&f#" + rank + " &7/ " + ranking.size()));
        set(12, item(Material.GOLDEN_HELMET, "&b&l" + lm.tr(viewer, "teaminfo.wins"),
                "&f" + team.getWins()));

        // From the ledger: the team's best game and top scorer of this event.
        Map<String, Integer> byGame = new HashMap<>();
        Map<UUID, Integer> byPlayer = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        PointsLedger ledger = plugin.getPointsLedger();
        if (ledger != null) {
            for (PointsLedger.Entry e : ledger.all()) {
                if (!team.getId().equals(e.teamId())) continue;
                byGame.merge(e.gameId(), e.points(), Integer::sum);
                if (e.player() != null) {
                    byPlayer.merge(e.player(), e.points(), Integer::sum);
                    names.put(e.player(), e.playerName());
                }
            }
        }
        var bestGame = byGame.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        var bestPlayer = byPlayer.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        set(14, item(Material.TARGET, "&d&l" + lm.tr(viewer, "teaminfo.best-game"),
                bestGame != null ? "&f" + prettify(bestGame.getKey()) + " &7(" + bestGame.getValue() + ")"
                        : "&8" + lm.tr(viewer, "teaminfo.none")));
        set(15, item(Material.DIAMOND_SWORD, "&c&l" + lm.tr(viewer, "teaminfo.top-scorer"),
                bestPlayer != null ? "&f" + names.getOrDefault(bestPlayer.getKey(), "?") + " &7(" + bestPlayer.getValue() + ")"
                        : "&8" + lm.tr(viewer, "teaminfo.none")));

        // Members: heads on row 3
        int slot = 19;
        for (UUID id : team.getMembers()) {
            if (slot > 25) break;
            var op = Bukkit.getOfflinePlayer(id);
            String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
            int pts = byPlayer.getOrDefault(id, 0);
            set(slot++, head(op, team.getColor() + name,
                    "&7" + lm.tr(viewer, "teaminfo.event-points") + ": &f" + pts,
                    op.isOnline() ? "&a● online" : "&8● offline"));
        }

        // Other teams strip
        int s = 28;
        for (KMCTeam other : ranking) {
            if (s > 34) break;
            ItemStack icon = plugin.getTeamEmblems().banner(other);
            ItemMeta im = icon.getItemMeta();
            im.setLore(List.of("§7" + other.getPoints() + " " + lm.tr(viewer, "teaminfo.pts"),
                    "§e" + lm.tr(viewer, "teaminfo.click")));
            icon.setItemMeta(im);
            button(s++, icon, p -> new TeamProfileGui(plugin, p, other).open(p));
        }
        fillEmpty();
    }

    private static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
