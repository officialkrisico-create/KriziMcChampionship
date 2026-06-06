package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.lang.LanguageManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Language picker — choose your personal language (e.g. Nederlands / English). */
public final class LanguageGui extends Gui {

    private final KMCCore plugin;

    public LanguageGui(KMCCore plugin, Player viewer) {
        super(titleFor(plugin, viewer), 3);
        this.plugin = plugin;
        render(viewer);
    }

    private static String titleFor(KMCCore plugin, Player viewer) {
        return plugin.getLanguageManager().tr(viewer, "language.gui-title");
    }

    private void render(Player viewer) {
        LanguageManager lang = plugin.getLanguageManager();
        String current = lang.getLanguage(viewer.getUniqueId());

        set(4, item(Material.BOOK, "&6&l" + lang.tr(viewer, "language.gui-title"),
                "&7" + lang.tr(viewer, "language.current", lang.displayName(current))));

        List<String> codes = new ArrayList<>(lang.available());
        int[] slots = {11, 12, 13, 14, 15}; // centred row of options
        for (int i = 0; i < codes.size() && i < slots.length; i++) {
            String code = codes.get(i);
            boolean active = code.equals(current);
            button(slots[i], item(iconFor(code),
                    (active ? "&a&l" : "&e&l") + lang.displayName(code),
                    active ? "&a✔ Actief" : "&7Klik om te kiezen"),
                    p -> {
                        lang.setLanguage(p.getUniqueId(), code);
                        p.sendMessage(lang.tr(code, "language.set", lang.displayName(code)));
                        try { plugin.getScoreboardManager().forceRefreshPlayer(p); } catch (Exception ignored) {}
                        try { plugin.getTabListManager().updateTabList(p); } catch (Exception ignored) {}
                        new LanguageGui(plugin, p).open(p);
                    });
        }

        fillEmpty();
    }

    private static Material iconFor(String code) {
        return switch (code) {
            case "nl" -> Material.ORANGE_BANNER;
            case "en" -> Material.RED_BANNER;
            default   -> Material.WHITE_BANNER;
        };
    }
}
