package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.KMCTeam;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * What the Hall of Fame villager opens: the top ten players of the tournament (heads with their own skin),
 * the team standings as banners in the team emblem (click one for the team profile) and a button to the
 * all-time records.
 */
public final class HoFOverviewGui extends Gui {

    /** Top three get the middle of row two, ranks 4-10 fill row three. */
    private static final int[] PODIUM_SLOTS = {13, 11, 15};
    private static final int[] REST_SLOTS = {19, 20, 21, 22, 23, 24, 25};

    public HoFOverviewGui(KMCCore plugin, Player viewer) {
        super("&6&l🏆 Hall of Fame", 6);
        var lm = plugin.getLanguageManager();

        set(4, item(Material.NETHER_STAR, "&6&lHall of Fame",
                "&7De beste spelers en teams van dit toernooi."));

        List<PlayerData> board = plugin.getPlayerDataManager().getLeaderboard();
        for (int i = 0; i < Math.min(10, board.size()); i++) {
            PlayerData d = board.get(i);
            String medal = i == 0 ? "&6&l🥇 " : i == 1 ? "&7&l🥈 " : i == 2 ? "&c&l🥉 " : "&e#" + (i + 1) + " ";
            String name = d.getName() != null ? d.getName() : "?";
            var team = plugin.getTeamManager().getTeamByPlayer(d.getUuid());
            ItemStack head = head(Bukkit.getOfflinePlayer(d.getUuid()), medal + "&f" + name,
                    "&7Punten: &e" + d.getPoints(),
                    "&7Kills: &f" + d.getKills(),
                    "&7Wins: &f" + d.getWins(),
                    team != null ? "&7Team: " + team.getColor() + team.getDisplayName() : "&8Geen team");
            int slot = i < 3 ? PODIUM_SLOTS[i] : REST_SLOTS[i - 3];
            set(slot, head);
        }
        if (board.isEmpty()) {
            set(22, item(Material.GRAY_DYE, "&7Nog geen spelers", "&8Er zijn nog geen punten verdiend."));
        }

        set(31, item(Material.WHITE_BANNER, "&6&lTeamstand", "&7Klik op een team voor het profiel."));
        List<KMCTeam> teams = plugin.getTeamManager().getTeamsSortedByPoints();
        for (int i = 0; i < Math.min(9, teams.size()); i++) {
            KMCTeam t = teams.get(i);
            ItemStack banner = plugin.getTeamEmblems().banner(t);
            ItemMeta m = banner.getItemMeta();
            String medal = i == 0 ? "&6&l🥇 " : i == 1 ? "&7&l🥈 " : i == 2 ? "&c&l🥉 " : "&7#" + (i + 1) + " ";
            m.setDisplayName(nl.kmc.kmccore.util.MessageUtil.color(medal + t.getColor() + t.getDisplayName()));
            m.setLore(List.of("§7Punten: §e" + t.getPoints(),
                    "§7Spelers: §f" + t.getMembers().size(),
                    "§eKlik voor het teamprofiel"));
            banner.setItemMeta(m);
            button(36 + i, banner, p -> new TeamProfileGui(plugin, p, t).open(p));
        }

        button(49, item(Material.GOLD_BLOCK, "&6&lRecords aller tijden",
                        "&7De beste prestaties ooit in KMC.", "&eKlik om te openen"),
                p -> new HallOfFameGui(plugin).open(p));

        fillEmpty();
    }
}
