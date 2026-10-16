package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.checklist.Checklist;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** The tournament checklist as a menu: click an item to tick it on or off. */
public final class ChecklistGui extends Gui {

    private static final int PER_PAGE = 45;

    public ChecklistGui(KMCCore plugin, int page) {
        super("&6&lChecklist &8— &7" + plugin.getChecklist().doneCount() + "/" + plugin.getChecklist().size(), 6);
        Checklist list = plugin.getChecklist();
        List<Checklist.Item> items = list.items();
        int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));

        for (int slot = 0; slot < PER_PAGE; slot++) {
            int idx = p * PER_PAGE + slot;
            if (idx >= items.size()) break;
            Checklist.Item it = items.get(idx);
            final int index = idx;
            List<String> lore = new ArrayList<>();
            lore.add(it.done() ? "&aAfgevinkt" : "&cNog te doen");
            lore.add("");
            lore.add("&eKlik om af te vinken / terug te zetten");
            if (!it.standard()) lore.add("&8Eigen item: /kmcchecklist remove " + (idx + 1));
            button(slot, item(it.done() ? Material.LIME_DYE : Material.RED_DYE,
                            (it.done() ? "&a✔ &7" : "&c✘ &f") + it.text(), lore.toArray(new String[0])),
                    pl -> {
                        list.toggle(index);
                        new ChecklistGui(plugin, p).open(pl);
                    });
        }

        int done = list.doneCount();
        set(49, item(list.complete() ? Material.NETHER_STAR : Material.CLOCK,
                list.complete() ? "&a&lAlles klaar!" : "&e&lVoortgang: &f" + done + "/" + list.size(),
                "&7Voeg zelf iets toe: &e/kmcchecklist add <tekst>"));
        if (p > 0) button(45, item(Material.ARROW, "&7« Vorige pagina"), pl -> new ChecklistGui(plugin, p - 1).open(pl));
        if (p < pages - 1) button(53, item(Material.ARROW, "&7Volgende pagina »"), pl -> new ChecklistGui(plugin, p + 1).open(pl));
        button(47, item(Material.BARRIER, "&c&lAlles uitvinken",
                        "&7Begin een nieuw evenement:", "&7alle vinkjes gaan weg.", "&eKlik"),
                pl -> {
                    list.reset();
                    new ChecklistGui(plugin, p).open(pl);
                });
        fillEmpty();
    }
}
