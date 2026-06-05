package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.HallOfFameManager;
import nl.kmc.kmccore.models.HoFRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;

/**
 * Hall of Fame browser — the all-time record holder for each category, shown
 * as a player head with the value and the event it was set in.
 */
public final class HallOfFameGui extends Gui {

    public HallOfFameGui(KMCCore plugin) {
        super("&6&l✦ Hall of Fame ✦", 6);
        render(plugin);
    }

    private void render(KMCCore plugin) {
        HallOfFameManager hof = plugin.getHallOfFameManager();

        set(4, item(Material.GOLD_BLOCK, "&6&lHall of Fame",
                "&7De beste prestaties aller tijden in KMC."));

        int slot = 19;
        for (HallOfFameManager.Category cat : hof.getCategories()) {
            if (slot >= 44) break;
            HoFRecord rec = hof.getRecord(cat.id);
            if (rec != null && rec.getPlayerUuid() != null) {
                set(slot, head(Bukkit.getOfflinePlayer(rec.getPlayerUuid()),
                        "&e&l" + cat.displayName,
                        "&7Houder: &f" + rec.getPlayerName(),
                        "&7Waarde: &a" + rec.getValue(),
                        "&7Gezet in: &fKMC #" + rec.getEventNumber()));
            } else {
                set(slot, item(Material.GRAY_DYE, "&7&l" + cat.displayName,
                        "&8Nog geen record gezet"));
            }
            slot++;
            if (slot % 9 == 8) slot += 1;
        }

        fillEmpty();
    }
}
