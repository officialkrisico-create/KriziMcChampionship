package nl.kmc.kmccore.gui;

import nl.kmc.core.validation.ValidationReport;
import nl.kmc.core.validation.ValidationReport.Status;
import nl.kmc.core.validation.Validator;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Material;

/** Detailed check list for one validator, with fix hints + a jump to setup. */
public final class ValidationDetailGui extends Gui {

    public ValidationDetailGui(KMCCore plugin, Validator v) {
        super("&1Validatie: &9" + v.displayName(), 6);
        render(plugin, v);
    }

    private void render(KMCCore plugin, Validator v) {
        ValidationReport rep;
        try { rep = v.validate(); }
        catch (Throwable t) { rep = new ValidationReport(); rep.error("Validator", "crashte: " + t, null); }

        Status st = rep.overall();
        set(4, item(st == Status.NOT_READY ? Material.RED_CONCRETE
                        : st == Status.PLAYABLE ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE,
                "&f&l" + v.displayName(),
                "&7Score: &e" + rep.scorePercent() + "%",
                "&7Status: " + (st == Status.NOT_READY ? "&cNOT READY" : st == Status.PLAYABLE ? "&ePLAYABLE" : "&aREADY")));

        int slot = 18;
        for (ValidationReport.Check c : rep.getChecks()) {
            if (slot >= 45) break;
            Material glass = switch (c.status()) {
                case READY -> Material.LIME_STAINED_GLASS_PANE;
                case PLAYABLE -> Material.YELLOW_STAINED_GLASS_PANE;
                case NOT_READY -> Material.RED_STAINED_GLASS_PANE;
            };
            String col = c.status() == Status.NOT_READY ? "&c" : c.status() == Status.PLAYABLE ? "&e" : "&a";
            String icon = c.status() == Status.NOT_READY ? "✗" : c.status() == Status.PLAYABLE ? "⚠" : "✓";
            String[] lore = (c.fixHint() != null && !c.fixHint().isBlank())
                    ? new String[]{"&7" + c.message(), "&7", "&eFix: &f" + c.fixHint()}
                    : new String[]{"&7" + c.message()};
            set(slot, item(glass, col + icon + " " + c.name(), lore));
            slot++;
            if (slot % 9 == 8) slot += 1;
        }

        button(48, item(Material.ANVIL, "&a&lFix Problem",
                "&7Open de Setup Dashboard om dit op te lossen."),
                p -> new SetupDashboardGui(plugin).open(p));
        button(49, item(Material.ARROW, "&7« Terug"),
                p -> new ValidationCenterGui(plugin).open(p));

        fillEmpty();
    }
}
