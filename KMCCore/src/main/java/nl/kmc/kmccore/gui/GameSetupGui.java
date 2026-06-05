package nl.kmc.kmccore.gui;

import nl.kmc.core.setup.GameSetup;
import nl.kmc.core.setup.SetupStep;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Per-game setup checklist. Shows every {@link SetupStep} for a game with a
 * done / not-done light; clickable steps run their action (e.g. "add spawn at
 * your location") and refresh the screen live.
 */
public final class GameSetupGui extends Gui {

    private final KMCCore   plugin;
    private final GameSetup setup;

    public GameSetupGui(KMCCore plugin, GameSetup setup) {
        super("&1Setup: &9" + setup.displayName(), 6);
        this.plugin = plugin;
        this.setup  = setup;
    }

    @Override
    public void open(Player p) {
        render(p);
        super.open(p);
    }

    private void render(Player viewer) {
        inventory.clear();
        clearActions();

        boolean ready = safeReady();
        set(4, item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                "&f&l" + setup.displayName(),
                ready ? "&aKlaar om te spelen ✓" : "&cNog niet klaar — zie hieronder ✗"));

        List<SetupStep> steps;
        try { steps = setup.steps(viewer); } catch (Throwable t) { steps = List.of(); }

        int slot = 18;
        for (SetupStep step : steps) {
            if (slot >= 53) break;
            String light = step.isDone() ? "&a" : "&c";
            String[] lore = step.hasAction()
                    ? new String[]{ light + step.getStatus(), "&7", "&e" + step.getActionHint() }
                    : new String[]{ light + step.getStatus() };
            if (step.hasAction()) {
                final SetupStep s = step;
                button(slot, item(step.getIcon(), "&f" + step.getLabel(), lore),
                        p -> {
                            try { s.getAction().accept(p); } catch (Throwable t) {
                                p.sendMessage("§cActie mislukt: " + t.getMessage());
                            }
                            render(p);          // refresh state
                            p.updateInventory();
                        });
            } else {
                set(slot, item(step.getIcon(), "&f" + step.getLabel(), lore));
            }
            slot++;
            if (slot % 9 == 8) slot += 1;
        }

        // Back button.
        button(49, item(Material.ARROW, "&7« Terug naar dashboard"),
                p -> new SetupDashboardGui(plugin).open(p));

        fillEmpty();
    }

    private boolean safeReady() {
        try { return setup.isReady(); } catch (Throwable t) { return false; }
    }
}
