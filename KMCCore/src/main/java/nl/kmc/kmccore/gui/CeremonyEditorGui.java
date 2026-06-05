package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.managers.CeremonyManager;
import org.bukkit.Material;

import java.util.Map;

/**
 * Ceremony editor — lists the tournament's ceremony phases; click one to edit
 * its title, subtitle, messages and duration in-menu.
 */
public final class CeremonyEditorGui extends Gui {

    private static final Map<String, String> NO_PH = Map.of();

    public CeremonyEditorGui(KMCCore plugin) {
        super("&1&lCeremonie Editor", 4);
        render(plugin);
    }

    private void render(KMCCore plugin) {
        CeremonyManager cm = plugin.getCeremonyManager();
        set(4, item(Material.WRITABLE_BOOK, "&6&lCeremonies",
                "&7Klik op een fase om de teksten,",
                "&7titels en duur aan te passen."));

        int slot = 9;
        for (String phase : CeremonyManager.PHASES) {
            if (slot >= 36) break;
            int dur = cm.getDuration(phase, 8);
            int msgs = cm.getMessages(phase, NO_PH).size();
            button(slot, item(Material.PAPER, "&e&l" + phase,
                    "&7Duur: &f" + dur + "s",
                    "&7Berichten: &f" + msgs,
                    "&aKlik om te bewerken"),
                    p -> new CeremonyPhaseGui(plugin, phase).open(p));
            slot++;
            if (slot % 9 == 8) slot += 1;
        }
        fillEmpty();
    }
}
