package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Help / Wiki GUI — explains the tournament and lists every game. Click a game
 * to open a detailed explanation ({@link HelpDetailGui}).
 */
public final class HelpGui extends Gui {

    private final KMCCore plugin;

    public HelpGui(KMCCore plugin) {
        super("&1&lKMC — Hoe werkt het?", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        set(4, item(Material.ENCHANTED_BOOK, "&6&l" + plugin.getConfig().getString("tournament.name", "KMC Toernooi"),
                "&7Teams strijden in meerdere mini-games",
                "&7om de meeste punten. Het team met de",
                "&7meeste punten aan het eind wint!",
                "",
                "&7• Punten tellen voor jou én je team",
                "&7• Latere rondes geven meer punten",
                "&7• Stem op de volgende game met &e/kmcvote",
                "",
                "&eKlik op een game voor de volledige uitleg."));

        List<GameRegistration> regs = new ArrayList<>();
        GameRegistryService registry = registry();
        if (registry != null) regs.addAll(registry.getAll());

        // Centred 7-wide grid (columns 1-7, rows 2-4).
        int idx = 0;
        if (!regs.isEmpty()) {
            for (GameRegistration reg : regs) {
                int slot = (2 + idx / 7) * 9 + (1 + idx % 7);
                if (slot >= 45) break;
                button(slot, item(reg.getIcon() != null ? reg.getIcon() : Material.PAPER,
                        "&e&l" + reg.getDisplayName(),
                        "&7" + safe(reg.getDescription()),
                        "",
                        "&eKlik voor uitleg"),
                        p -> new HelpDetailGui(plugin, reg).open(p));
                idx++;
            }
        } else {
            // V1 fallback (no rich descriptions available).
            for (var g : plugin.getGameManager().getAllGames()) {
                int slot = (2 + idx / 7) * 9 + (1 + idx % 7);
                if (slot >= 45) break;
                set(slot, item(g.getIcon() != null ? g.getIcon() : Material.PAPER,
                        "&e&l" + g.getDisplayName(),
                        "&7Min. spelers: &f" + g.getMinPlayers()));
                idx++;
            }
        }

        button(49, item(Material.BARRIER, "&c&lSluiten"), org.bukkit.entity.Player::closeInventory);
        fillEmpty();
    }

    private static String safe(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private GameRegistryService registry() {
        if (Bukkit.getPluginManager().getPlugin("KMCCoreV2") instanceof nl.kmc.core.KMCCorePlugin v2) {
            return v2.getContainer().get(GameRegistryService.class);
        }
        return null;
    }
}
