package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.tournament.FanFavoriteManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-tournament "Fan Favorite" vote — every online player is a candidate;
 * click a head to vote for them. Opened by {@link FanFavoriteManager}.
 */
public final class FanFavoriteVoteGui extends Gui {

    public FanFavoriteVoteGui(KMCCore plugin, FanFavoriteManager manager, Player viewer) {
        super("&1&l🌟 Fan Favorite — stem!", 6);

        // You cannot vote for yourself or for your own team.
        var myTeam = plugin.getTeamManager().getTeamByPlayer(viewer.getUniqueId());
        List<Player> candidates = new ArrayList<>();
        for (Player c : Bukkit.getOnlinePlayers()) {
            if (c.getUniqueId().equals(viewer.getUniqueId())) continue;
            var t = plugin.getTeamManager().getTeamByPlayer(c.getUniqueId());
            if (myTeam != null && t != null && t.getId().equals(myTeam.getId())) continue;
            candidates.add(c);
        }
        candidates.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        set(4, item(Material.NETHER_STAR, "&6&lWie was jouw Fan Favorite?",
                "&7Klik op een speler om te stemmen.",
                "&7Je kan je stem nog wijzigen tot de stemming sluit."));

        int slot = 9;
        for (Player candidate : candidates) {
            if (slot >= 54) break;       // 45 candidates fit (8 teams of 4 = 28 without your own team)
            OfflinePlayer off = candidate;
            button(slot, head(off, "&e&l" + candidate.getName(),
                            "&7Klik om op deze speler te stemmen"),
                    voter -> {
                        manager.castVote(voter, off.getUniqueId());
                        voter.closeInventory();
                    });
            slot++;
        }

        fillEmpty();
    }
}
