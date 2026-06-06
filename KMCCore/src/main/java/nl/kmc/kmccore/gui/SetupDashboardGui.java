package nl.kmc.kmccore.gui;

import nl.kmc.core.setup.GameSetup;
import nl.kmc.core.setup.SetupService;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The unified Setup Dashboard — one screen to configure the lobby and every
 * game, replacing the scattered {@code /kmcarena} and {@code /<game> set...}
 * command trees. Each game shows a live ready / not-ready light; click it to
 * open that game's setup checklist.
 */
public final class SetupDashboardGui extends Gui {

    private final KMCCore plugin;

    public SetupDashboardGui(KMCCore plugin) {
        super("&1&lKMC Setup Dashboard", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        inventory.clear();
        clearActions();

        // ── Lobby tile ────────────────────────────────────────────────────────
        boolean lobbySet = plugin.getArenaManager().getLobby() != null;
        button(4, item(Material.BEACON,
                "&b&lLobby",
                lobbySet ? "&aIngesteld ✓" : "&cNiet ingesteld ✗",
                "&7De plek waar spelers tussen games staan.",
                "&eKlik: zet de lobby op jouw locatie"),
                p -> {
                    plugin.getArenaManager().setLobby(p.getLocation());
                    p.sendMessage("§a[Setup] Lobby gezet op jouw locatie.");
                    render(); p.updateInventory();
                });

        // ── Game grid ─────────────────────────────────────────────────────────
        SetupService setup = setupService();
        if (setup == null || setup.getAll().isEmpty()) {
            set(22, item(Material.BARRIER, "&cGeen games geregistreerd",
                    "&7Start de game-plugins zodat ze zich registreren."));
            fillEmpty();
            return;
        }

        // Centered 7-wide grid (columns 1-7, rows 1-4) — clean & balanced.
        int idx = 0;
        for (GameSetup gs : setup.getAll()) {
            int slot = (1 + idx / 7) * 9 + (1 + idx % 7);
            if (slot >= 45) break;
            boolean ready = safeReady(gs);
            button(slot, item(gs.icon(),
                    "&f&l" + gs.displayName(),
                    ready ? "&aKlaar om te spelen ✓" : "&cNog niet klaar ✗",
                    "&7",
                    "&eKlik om in te stellen"),
                    p -> new GameSetupGui(plugin, gs).open(p));
            idx++;
        }

        // Summary tile.
        long ready = setup.getAll().stream().filter(this::safeReady).count();
        long total = setup.getAll().size();
        set(49, item(ready == total ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                "&f&lOverzicht",
                "&7Klaar: &a" + ready + "&7/&e" + total + " &7games",
                ready == total ? "&aAlles is klaar voor het toernooi!" : "&eNog niet alle games zijn klaar."));

        fillEmpty();
    }

    private boolean safeReady(GameSetup gs) {
        try { return gs.isReady(); } catch (Throwable t) { return false; }
    }

    private SetupService setupService() {
        if (Bukkit.getPluginManager().getPlugin("KMCCoreV2") instanceof nl.kmc.core.KMCCorePlugin v2) {
            return v2.getContainer().get(SetupService.class);
        }
        return null;
    }
}
