package nl.kmc.kmccore.gui;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.lang.LanguageManager;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Language picker — pick your language via a recognisable flag banner. */
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
        int n = codes.size();
        int start = 13 - n / 2;               // centred on the middle row
        for (int i = 0; i < n; i++) {
            String code = codes.get(i);
            boolean active = code.equals(current);
            button(start + i, flagItem(code, lang.displayName(code), active,
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

    // ── Flag banners ────────────────────────────────────────────────────────

    private ItemStack flagItem(String code, String displayName, boolean active, String statusLore) {
        ItemStack it = flagBanner(code);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    (active ? "&a&l" : "&e&l") + displayName));
            m.setLore(List.of(ChatColor.translateAlternateColorCodes('&', statusLore)));
            if (active) {
                m.addEnchant(Enchantment.UNBREAKING, 1, true);
                m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            it.setItemMeta(m);
        }
        return it;
    }

    /** Builds a banner that approximates the country's flag. Falls back to a plain banner. */
    private ItemStack flagBanner(String code) {
        Material base;
        List<Pattern> patterns = new ArrayList<>();
        switch (code) {
            case "nl" -> { base = Material.WHITE_BANNER;
                addPattern(patterns, DyeColor.RED,  "stripe_top");
                addPattern(patterns, DyeColor.BLUE, "stripe_bottom"); }
            case "en" -> { base = Material.WHITE_BANNER;
                addPattern(patterns, DyeColor.RED,  "straight_cross"); }
            case "tr" -> { base = Material.RED_BANNER;
                addPattern(patterns, DyeColor.WHITE, "circle"); }
            default   -> base = Material.WHITE_BANNER;
        }
        ItemStack it = new ItemStack(base);
        if (it.getItemMeta() instanceof BannerMeta bm) {
            for (Pattern p : patterns) { try { bm.addPattern(p); } catch (Throwable ignored) {} }
            it.setItemMeta(bm);
        }
        return it;
    }

    private void addPattern(List<Pattern> list, DyeColor color, String key) {
        PatternType type = patternType(key);
        if (type != null) list.add(new Pattern(color, type));
    }

    /** Looks up a banner pattern by key — version-safe (no enum constant references). */
    private static PatternType patternType(String key) {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.BANNER_PATTERN)
                    .get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;
        }
    }
}
