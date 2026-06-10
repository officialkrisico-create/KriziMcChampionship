package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.models.KMCGame;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Flyover editor — one tile per game showing its cinematic flyover route
 * ({@code arena-<gameId>}). Build a flyover by standing where you want a camera
 * shot and clicking; preview it; or clear it. Reuses the existing camera engine
 * (CinematicManager) — no duplicate system.
 *
 * <p>Left-click = add a camera point here · Right-click = preview · Shift = clear.
 */
public final class FlyoverDashboardGui extends Gui {

    private final KMCCore plugin;

    public FlyoverDashboardGui(KMCCore plugin) {
        super("&1&lFlyovers", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();

        set(4, item(Material.ELYTRA, "&b&lFlyover-editor",
                "&7Cinematische camerasweep vóór elke game.",
                "",
                "&aLinks&7: voeg camerapunt toe (jouw positie)",
                "&eRechts&7: preview de flyover",
                "&7Voor fijn bewerken: &e/kmccamera"));

        List<KMCGame> games = new ArrayList<>(plugin.getGameManager().getAllGames());
        games.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        int min = plugin.getConfig().getInt("flyover.min-points", 2);

        int idx = 0;
        for (KMCGame game : games) {
            int slot = (1 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 45) break;
            int points = plugin.getCinematicManager().getRoute("arena-" + game.getId()).map(CameraRoute::size).orElse(0);
            boolean ok = points >= min;
            set(slot, item(game.getIcon() != null ? game.getIcon() : Material.PAPER,
                    (ok ? "&a&l" : "&e&l") + game.getDisplayName(),
                    ok ? "&aFlyover klaar ✓ &7(" + points + " punten)"
                       : "&7Camerapunten: &e" + points + "&7/&e" + min,
                    "",
                    "&aLinks&7: camerapunt toevoegen",
                    "&eRechts&7: preview"));
            idx++;
        }

        button(49, item(Material.BARRIER, "&c&lSluiten"), Player::closeInventory);
        fillEmpty();
    }

    @Override
    public void handleClick(Player p, int slot, boolean rightClick) {
        boolean grid = slot >= 9 && slot < 45 && slot % 9 != 0 && slot % 9 != 8;
        if (!grid) { super.handleClick(p, slot, rightClick); return; }

        List<KMCGame> games = new ArrayList<>(plugin.getGameManager().getAllGames());
        games.sort((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        int idx = (slot / 9 - 1) * 7 + (slot % 9 - 1);
        if (idx < 0 || idx >= games.size()) return;
        KMCGame game = games.get(idx);
        String routeId = "arena-" + game.getId();

        if (rightClick) {
            Optional<CameraRoute> route = plugin.getCinematicManager().getRoute(routeId);
            if (route.isEmpty() || route.get().isEmpty()) {
                p.sendMessage("§c[Flyover] Nog geen camerapunten voor " + game.getDisplayName() + ".");
                return;
            }
            p.closeInventory();
            p.sendMessage("§a[Flyover] Preview van " + game.getDisplayName() + "...");
            plugin.getCinematicManager().previewRoute(route.get(), p, null);
            return;
        }

        // Add a camera point at the admin's current position/orientation.
        int dur = plugin.getConfig().getInt("flyover.point-duration-ticks", 60);
        CameraWaypoint wp = new CameraWaypoint(p.getLocation(), dur, InterpolationType.SMOOTH, "", "", "");
        int total = plugin.getCinematicManager().appendWaypoint(routeId, "Flyover — " + game.getDisplayName(), wp);
        p.sendMessage("§a[Flyover] Camerapunt #" + total + " toegevoegd aan " + game.getDisplayName() + ".");
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
        render();
        p.updateInventory();
    }
}
