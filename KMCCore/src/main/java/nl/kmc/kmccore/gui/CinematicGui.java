package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import org.bukkit.Material;

/**
 * Cinematic manager — lists saved camera routes; click to preview, or toggle
 * delete-mode to remove one. (Recording new routes stays on {@code /kmccamera},
 * since you fly to each waypoint position.)
 */
public final class CinematicGui extends Gui {

    private final KMCCore plugin;
    private boolean deleteMode = false;

    public CinematicGui(KMCCore plugin) {
        super("&1&lCinematics", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();

        set(4, item(Material.MOJANG_BANNER_PATTERN, "&6&lCamera-routes",
                "&7Klik op een route om te previewen.",
                "&7Nieuwe routes maak je met &e/kmccamera create"));

        // Delete-mode toggle.
        button(8, item(deleteMode ? Material.REDSTONE_BLOCK : Material.BARRIER,
                deleteMode ? "&c&lVerwijder-modus AAN" : "&7Verwijder-modus uit",
                "&7Klik om te wisselen.",
                deleteMode ? "&cKlik op een route om hem te verwijderen!" : "&7In deze modus preview je routes."),
                p -> { deleteMode = !deleteMode; render(); p.updateInventory(); });

        int slot = 18;
        var routes = plugin.getCinematicManager().getAllRoutes();
        if (routes.isEmpty()) {
            set(22, item(Material.GRAY_DYE, "&7Geen routes",
                    "&8Maak er een met /kmccamera create <naam>"));
        }
        for (CameraRoute r : routes) {
            if (slot >= 53) break;
            button(slot, item(Material.ENDER_EYE,
                    (deleteMode ? "&c" : "&e") + "&l" + r.getId(),
                    "&7" + r.getDescription(),
                    "&7Waypoints: &f" + r.size() + " &7• &f" + (r.totalTicks() / 20) + "s",
                    deleteMode ? "&cKlik om te VERWIJDEREN" : "&aKlik om te previewen"),
                    p -> {
                        if (deleteMode) {
                            plugin.getCinematicManager().deleteRoute(r.getId());
                            p.sendMessage("§c[Cinematic] Route §e" + r.getId() + "§c verwijderd.");
                            render(); p.updateInventory();
                        } else {
                            p.closeInventory();
                            plugin.getCinematicManager().previewRoute(r, p,
                                    () -> p.sendMessage("§a[Cinematic] Preview klaar."));
                        }
                    });
            slot++;
            if (slot % 9 == 8) slot += 1;
        }

        button(49, item(Material.LIME_DYE, "&a&lHerlaad routes",
                "&7Herlaadt cameras.yml van schijf."),
                p -> { plugin.getCinematicManager().reload(); render(); p.updateInventory();
                       p.sendMessage("§a[Cinematic] Routes herladen."); });

        fillEmpty();
    }
}
