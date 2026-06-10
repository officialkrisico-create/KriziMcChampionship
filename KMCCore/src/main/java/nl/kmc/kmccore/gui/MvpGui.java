package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.tournament.TournamentDataStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/** MVP board — this tournament's MVPs and the all-time MVP leaders. */
public final class MvpGui extends Gui {

    public MvpGui(KMCCore plugin, UUID viewer) {
        super("&6&lKMC MVPs", 6);
        render(plugin, viewer);
    }

    private void render(KMCCore plugin, UUID viewer) {
        TournamentDataStore store = plugin.getTournamentDataStore();

        set(4, item(Material.NETHER_STAR, "&6&lGame MVPs",
                "&7Na elke game wordt de beste speler gekozen.",
                "&7Jij dit toernooi: &e" + store.getTournamentMvp(viewer) + " MVP('s)",
                "&7Jij ooit: &e" + store.getLifetimeMvp(viewer) + " MVP('s)"));

        column(store, 10, "§6§lDIT TOERNOOI", store.topMvp(false, 5), false);
        column(store, 14, "§b§lALLER-TIJDEN",  store.topMvp(true, 5),  true);

        fillEmpty();
    }

    private void column(TournamentDataStore store, int topSlot, String title, List<UUID> ids, boolean lifetime) {
        set(topSlot, item(Material.GOLD_BLOCK, title, "&7Top MVP-spelers"));
        int[] slots = { topSlot + 9, topSlot + 18, topSlot + 27, topSlot + 36 };
        for (int i = 0; i < Math.min(slots.length, ids.size()); i++) {
            UUID id = ids.get(i);
            int count = lifetime ? store.getLifetimeMvp(id) : store.getTournamentMvp(id);
            String medal = switch (i) { case 0 -> "&6#1"; case 1 -> "&7#2"; case 2 -> "&c#3"; default -> "&7#" + (i + 1); };
            set(slots[i], head(Bukkit.getOfflinePlayer(id),
                    medal + " &f" + store.getMvpName(id), "&7MVP's: &e" + count));
        }
    }
}
