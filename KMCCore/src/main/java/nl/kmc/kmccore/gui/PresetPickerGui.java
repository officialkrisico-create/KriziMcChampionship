package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Picks an event preset in the {@code /kmcauto start} menu; applying one returns to that menu. */
public final class PresetPickerGui extends Gui {

    public PresetPickerGui(KMCCore plugin, Consumer<Player> onStartNow) {
        super("&1&lKies een preset", 3);
        List<String> names = plugin.getDirector().presets().names();
        for (int i = 0; i < Math.min(9, names.size()); i++) {
            String name = names.get(i);
            List<String> lore = new ArrayList<>(plugin.getDirector().presets().describe(name));
            lore.add("");
            lore.add("&eKlik om toe te passen");
            button(9 + i, item(Material.BOOK, "&b&l" + name, lore.toArray(new String[0])), p -> {
                List<String> report = new ArrayList<>();
                plugin.getDirector().presets().apply(name, report);
                report.forEach(l -> p.sendMessage(MessageUtil.color("&6[KMC] " + l)));
                new AutomationSetupGui(plugin, onStartNow).open(p);
            });
        }
        button(Math.min(17, 9 + Math.min(9, names.size())), item(Material.NETHER_STAR, "&a&lMaak een preset",
                        "&7Slaat je huidige instellingen op",
                        "&7(games, pauzes, finale, multipliers)",
                        "&7onder een naam die je in de chat typt.",
                        "",
                        "&eKlik om te maken"),
                p -> plugin.getChatInput().await(p, "Hoe moet de preset heten? (één woord)", text -> {
                    String name = text.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
                    if (name.isEmpty()) {
                        p.sendMessage(MessageUtil.color("&c[KMC] Die naam kan niet. Gebruik letters en cijfers."));
                    } else {
                        plugin.getDirector().presets().saveCurrent(name);
                        p.sendMessage(MessageUtil.color("&a[KMC] Preset &e" + name + "&a gemaakt van je huidige instellingen."));
                    }
                    new PresetPickerGui(plugin, onStartNow).open(p);
                }));
        button(22, item(Material.ARROW, "&7« Terug"), p -> new AutomationSetupGui(plugin, onStartNow).open(p));
        fillEmpty();
    }
}
