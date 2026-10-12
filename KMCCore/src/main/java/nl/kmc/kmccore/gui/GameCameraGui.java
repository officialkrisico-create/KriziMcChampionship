package nl.kmc.kmccore.gui;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.presentation.CinematicManager;
import nl.kmc.kmccore.presentation.camera.CameraRoute;
import nl.kmc.kmccore.presentation.camera.CameraWaypoint;
import nl.kmc.kmccore.presentation.camera.InterpolationType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Per-game camera setup, opened with {@code /<game> cam} (e.g. {@code /bp cam}). Lets an admin record, preview
 * and replace the flyovers of ONE game without knowing route ids:
 *
 * <ul>
 *   <li>{@code arena-<game>}     — the flyover shown before the game starts (used by every start flow)</li>
 *   <li>{@code game-intro-<game>} — the opening shot during the tournament intro</li>
 *   <li>{@code winner-<game>}    — the shot shown when the game ends</li>
 * </ul>
 *
 * Recording hands out a camera wand: right-click adds a waypoint at your position, shift-right-click reopens this
 * menu (save / preview / cancel).
 */
public final class GameCameraGui extends Gui {

    public static final String WAND_KEY = "camera_wand";

    private static final String[][] ROUTES = {
            {"arena-%s",       "Arena-flyover",   "Voor de start van de game (iedereen ziet dit)"},
            {"game-intro-%s",  "Game-intro",      "Openingsshot tijdens het toernooi"},
            {"winner-%s",      "Winnaar-shot",    "Camera aan het einde van de game"},
    };
    private static final int[] ROUTE_SLOTS = {10, 13, 16};

    private final KMCCore plugin;
    private final String gameId;
    private final String gameName;
    private int selected = 0;

    public GameCameraGui(KMCCore plugin, String gameId, String gameName, Player viewer) {
        super("&1&lCamera — " + gameName, 4);
        this.plugin   = plugin;
        this.gameId   = gameId;
        this.gameName = gameName;
        // Reopen on the route that is currently being recorded.
        plugin.getCinematicManager().getPendingRoute(viewer.getUniqueId()).ifPresent(r -> {
            for (int i = 0; i < ROUTES.length; i++) if (routeId(i).equals(r.getId())) selected = i;
        });
        render(viewer);
    }

    private String routeId(int i) { return String.format(ROUTES[i][0], gameId); }

    private void render(Player p) {
        inventory.clear();
        clearActions();
        CinematicManager cm = plugin.getCinematicManager();
        boolean recording = cm.isRecording(p.getUniqueId());
        String routeId = routeId(selected);

        for (int i = 0; i < ROUTES.length; i++) {
            final int idx = i;
            var existing = cm.getRoute(routeId(i));
            boolean isSel = i == selected;
            boolean pendingThis = recording && cm.getPendingRoute(p.getUniqueId())
                    .map(r -> r.getId().equals(routeId(idx))).orElse(false);
            List<String> lore = new java.util.ArrayList<>();
            lore.add("&7" + ROUTES[i][2]);
            lore.add("&8" + routeId(i));
            if (pendingThis) lore.add("&6● Wordt opgenomen: &f" + cm.getPendingRoute(p.getUniqueId()).map(CameraRoute::size).orElse(0) + " punten");
            else lore.add(existing.map(r -> "&aIngesteld: &f" + r.size() + " punten, " + (r.totalTicks() / 20) + "s")
                    .orElse("&cNog niet ingesteld"));
            lore.add(isSel ? "&e▶ Geselecteerd" : "&7Klik om te selecteren");
            ItemStack it = item(existing.isPresent() ? Material.ENDER_EYE : Material.ENDER_PEARL,
                    (isSel ? "&e&l" : "&f&l") + ROUTES[i][1], lore.toArray(new String[0]));
            button(ROUTE_SLOTS[i], it, pl -> { selected = idx; render(pl); pl.updateInventory(); });
        }

        String sel = ROUTES[selected][1];
        if (!recording) {
            button(28, item(Material.LIME_DYE, "&a&lOpname starten",
                    "&7Neemt &f" + sel + " &7op. Je krijgt een camera-wand:",
                    "&7rechtsklik = punt toevoegen, shift+rechtsklik = dit menu.",
                    "&8Bestaande route wordt vervangen bij opslaan."),
                    pl -> startRecording(pl, routeId));
        } else {
            button(28, item(Material.LIME_DYE, "&a&lPunt hier toevoegen",
                    "&7Voegt je huidige positie en kijkrichting toe", "&7(40 ticks, vloeiend)."),
                    pl -> { addPoint(pl); render(pl); pl.updateInventory(); });
            button(29, item(Material.RED_DYE, "&c&lLaatste punt weg", "&7Haalt het laatst toegevoegde punt weg."),
                    pl -> { cm.removeLastWaypoint(pl.getUniqueId()); render(pl); pl.updateInventory(); });
            button(30, item(Material.EMERALD, "&a&lOpslaan", "&7Slaat de route op en sluit de opname."),
                    pl -> saveRecording(pl));
            button(31, item(Material.BARRIER, "&c&lAnnuleren", "&7Gooit de opname weg."),
                    pl -> { cm.discardRecording(pl.getUniqueId()); removeWand(pl);
                            pl.sendMessage("§c[Camera] Opname geannuleerd."); render(pl); pl.updateInventory(); });
        }

        boolean exists = cm.getRoute(routeId).isPresent();
        if (exists && !recording) {
            button(33, item(Material.SPYGLASS, "&b&lPreview", "&7Speel de route voor jezelf af."),
                    pl -> { pl.closeInventory();
                            cm.getRoute(routeId).ifPresent(r -> cm.previewRoute(r, pl,
                                    () -> pl.sendMessage("§a[Camera] Preview klaar."))); });
            button(34, item(Material.TNT, "&4&lRoute verwijderen", "&7Verwijdert &f" + routeId + "&7.",
                    "&cDe game valt terug op een automatische flyover."),
                    pl -> { cm.deleteRoute(routeId); pl.sendMessage("§c[Camera] §e" + routeId + "§c verwijderd.");
                            render(pl); pl.updateInventory(); });
        }

        set(4, item(Material.MOJANG_BANNER_PATTERN, "&6&l" + gameName,
                "&7Stel hier de camera-shots van deze game in.",
                "&8Zonder eigen route maakt de game een automatische flyover."));
        fillEmpty();
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void startRecording(Player p, String routeId) {
        plugin.getCinematicManager().startRecording(p.getUniqueId(), routeId, gameName + " — " + routeId);
        giveWand(p, routeId);
        p.closeInventory();
        p.sendMessage("§a[Camera] Opname gestart voor §e" + routeId + "§a. Vlieg naar je beginpunt en rechtsklik met de wand.");
        p.sendMessage("§7Shift+rechtsklik met de wand opent het menu (opslaan / preview / annuleren).");
    }

    /** Adds the player's current position as a waypoint. Used by the menu button and the wand. */
    public static boolean addPoint(KMCCore plugin, Player p) {
        var cm = plugin.getCinematicManager();
        if (!cm.isRecording(p.getUniqueId())) return false;
        cm.addWaypoint(p.getUniqueId(), new CameraWaypoint(p.getLocation(), 40, InterpolationType.SMOOTH, "", "", ""));
        int n = cm.getPendingRoute(p.getUniqueId()).map(CameraRoute::size).orElse(0);
        p.sendActionBar(net.kyori.adventure.text.Component.text("§a● Punt #" + n + " toegevoegd"));
        return true;
    }

    private void addPoint(Player p) { addPoint(plugin, p); }

    private void saveRecording(Player p) {
        var cm = plugin.getCinematicManager();
        int n = cm.getPendingRoute(p.getUniqueId()).map(CameraRoute::size).orElse(0);
        if (n < 2) {
            p.sendMessage("§c[Camera] Een route heeft minstens 2 punten (nu " + n + ").");
            return;
        }
        cm.saveRecording(p.getUniqueId()).ifPresent(r ->
                p.sendMessage("§a[Camera] §e" + r.getId() + "§a opgeslagen (" + r.size() + " punten, "
                        + (r.totalTicks() / 20) + "s)."));
        removeWand(p);
        render(p);
        p.updateInventory();
    }

    // ── Wand ──────────────────────────────────────────────────────────────────

    private void giveWand(Player p, String routeId) {
        ItemStack wand = item(Material.BLAZE_ROD, "&6&lCamera-wand &7(" + routeId + ")",
                "&7Rechtsklik: punt toevoegen op je positie",
                "&7Shift+rechtsklik: menu (opslaan / preview / annuleren)");
        ItemMeta meta = wand.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, WAND_KEY),
                PersistentDataType.STRING, gameId + "|" + gameName);
        wand.setItemMeta(meta);
        p.getInventory().addItem(wand);
    }

    private void removeWand(Player p) { removeWands(plugin, p); }

    public static void removeWands(KMCCore plugin, Player p) {
        NamespacedKey key = new NamespacedKey(plugin, WAND_KEY);
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.hasItemMeta()) continue;
            if (it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING)) inv.setItem(i, null);
        }
    }
}
