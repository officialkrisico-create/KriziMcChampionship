package nl.kmc.kmccore.gui;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;

/**
 * Help / Wiki GUI — explains the tournament and every game (objective,
 * description, player count) so new players can learn without leaving the game.
 */
public final class HelpGui extends Gui {

    public HelpGui(KMCCore plugin) {
        super("&1&lKMC — Hoe werkt het?", 6);
        render(plugin);
    }

    private void render(KMCCore plugin) {
        set(4, item(Material.ENCHANTED_BOOK, "&6&l" + plugin.getConfig().getString("tournament.name", "KMC Tournament"),
                "&7Teams strijden in meerdere mini-games",
                "&7om de meeste punten. Het team met de",
                "&7meeste punten aan het eind wint!",
                "&7",
                "&7• Punten tellen voor jou én je team",
                "&7• Latere rondes geven meer punten",
                "&7• Stem op de volgende game met &e/kmcvote"));

        int slot = 18;
        GameRegistryService registry = registry();
        if (registry != null && !registry.getAll().isEmpty()) {
            for (GameRegistration reg : registry.getAll()) {
                if (slot >= 53) break;
                set(slot, item(reg.getIcon() != null ? reg.getIcon() : Material.PAPER,
                        "&f&l" + reg.getDisplayName(),
                        "&7" + safe(reg.getDescription()),
                        "&7",
                        "&eDoel: &f" + safe(reg.getObjective()),
                        "&7Min. spelers: &f" + reg.getMinPlayers()));
                slot++;
                if (slot % 9 == 8) slot += 1;
            }
        } else {
            // Fallback to the V1 game list (less detail).
            for (var g : plugin.getGameManager().getAllGames()) {
                if (slot >= 53) break;
                set(slot, item(g.getIcon() != null ? g.getIcon() : Material.PAPER,
                        "&f&l" + g.getDisplayName(),
                        "&7Min. spelers: &f" + g.getMinPlayers()));
                slot++;
                if (slot % 9 == 8) slot += 1;
            }
        }

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
