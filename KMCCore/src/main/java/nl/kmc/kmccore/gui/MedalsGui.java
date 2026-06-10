package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.tournament.TournamentDataStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Medal cabinet — your gold/silver/bronze haul plus the Most Decorated leaderboard. */
public final class MedalsGui extends Gui {

    public MedalsGui(KMCCore plugin, UUID viewer) {
        super("&6&lKMC Medailles", 6);
        render(plugin, viewer);
    }

    private void render(KMCCore plugin, UUID viewer) {
        TournamentDataStore store = plugin.getTournamentDataStore();
        int[] mine = store.getMedals(viewer);

        set(4, item(Material.GOLD_BLOCK, "&6&lJouw medailles",
                "&7Top-3 finishes in mini-games leveren medailles op."));

        set(20, item(Material.GOLD_INGOT,  "&6&lGoud",    "&fAantal: &e" + mine[0], "&71e plaats"));
        set(22, item(Material.IRON_INGOT,  "&f&lZilver",  "&fAantal: &e" + mine[1], "&72e plaats"));
        set(24, item(Material.COPPER_INGOT,"&c&lBrons",   "&fAantal: &e" + mine[2], "&73e plaats"));

        set(31, item(Material.NETHER_STAR, "&e&lTotaal",
                "&7Medailles: &f" + store.totalMedals(viewer),
                "&7Medaillescore: &f" + store.medalScore(viewer)));

        // Most Decorated leaderboard along the bottom rows.
        set(36, item(Material.WRITABLE_BOOK, "&b&lMeest gedecoreerd", "&7De spelers met de meeste medailles"));
        List<UUID> top = store.mostDecorated(8);
        int[] slots = {37, 38, 39, 40, 41, 42, 43, 44};
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            UUID id = top.get(i);
            int[] m = store.getMedals(id);
            String medal = switch (i) { case 0 -> "&6#1"; case 1 -> "&7#2"; case 2 -> "&c#3"; default -> "&7#" + (i + 1); };
            set(slots[i], head(Bukkit.getOfflinePlayer(id),
                    medal + " &f" + store.getName(id),
                    "&6🥇 " + m[0] + "  &f🥈 " + m[1] + "  &c🥉 " + m[2],
                    "&7Score: &e" + store.medalScore(id)));
        }

        fillEmpty();
    }
}
