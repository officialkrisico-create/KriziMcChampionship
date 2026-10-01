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

    public FanFavoriteVoteGui(KMCCore plugin, FanFavoriteManager manager) {
        super("&1&l🌟 Fan Favorite — stem!", 4);

        List<Player> candidates = new ArrayList<>(Bukkit.getOnlinePlayers());
        candidates.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        set(4, item(Material.NETHER_STAR, "&6&lWie was jouw Fan Favorite?",
                "&7Klik op een speler om te stemmen.",
                "&7Je kan je stem nog wijzigen tot de stemming sluit."));

        int slot = 9;
        for (Player candidate : candidates) {
            if (slot >= 36) break;
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
