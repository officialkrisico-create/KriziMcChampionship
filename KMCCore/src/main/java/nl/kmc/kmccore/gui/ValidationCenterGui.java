package nl.kmc.kmccore.gui;

import nl.kmc.core.validation.ValidationReport;
import nl.kmc.core.validation.ValidationReport.Status;
import nl.kmc.core.validation.Validator;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.commands.ValidateCommand;
import nl.kmc.kmccore.validation.CoreValidators;
import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Event Validation Center — overall readiness + a tile per system. */
public final class ValidationCenterGui extends Gui {

    private final KMCCore plugin;

    public ValidationCenterGui(KMCCore plugin) {
        super("&1&lKMC Event Validation Center", 6);
        this.plugin = plugin;
        render();
    }

    private void render() {
        List<Validator> validators = CoreValidators.collectAll(plugin);

        Map<Validator, ValidationReport> reports = new LinkedHashMap<>();
        int totalScore = 0, ready = 0;
        boolean anyErr = false, anyWarn = false;
        for (Validator v : validators) {
            ValidationReport rep;
            try { rep = v.validate(); }
            catch (Throwable t) { rep = new ValidationReport(); rep.error("Validator", "crashte: " + t, null); }
            reports.put(v, rep);
            totalScore += rep.scorePercent();
            switch (rep.overall()) {
                case READY -> ready++;
                case PLAYABLE -> anyWarn = true;
                case NOT_READY -> anyErr = true;
            }
        }
        int overall = validators.isEmpty() ? 100 : totalScore / validators.size();
        String statusLabel = anyErr ? "&c&lNOT READY" : anyWarn ? "&e&lPLAYABLE" : "&a&lREADY";

        set(4, item(overall >= 90 ? Material.LIME_CONCRETE : overall >= 60 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE,
                "&f&lTournament Readiness: &e" + overall + "%",
                "&7Status: " + statusLabel,
                "&7Systemen klaar: &a" + ready + "&7/&f" + validators.size()));

        // Centered 7-wide grid in rows 1-4 (columns 1-7), leaving columns 0/8
        // and the top/bottom rows as a clean border.
        int idx = 0;
        for (Map.Entry<Validator, ValidationReport> e : reports.entrySet()) {
            int row = 1 + idx / 7;
            int col = 1 + idx % 7;
            int slot = row * 9 + col;
            if (slot >= 45) break;            // don't spill into the control row
            Validator v = e.getKey();
            ValidationReport rep = e.getValue();
            Status st = rep.overall();
            String c = st == Status.NOT_READY ? "&c" : st == Status.PLAYABLE ? "&e" : "&a";
            button(slot, item(v.icon() != null ? v.icon() : Material.PAPER,
                    c + "&l" + v.displayName(),
                    "&7Score: &f" + rep.scorePercent() + "%",
                    "&7Fouten: &c" + rep.countErrors() + " &7• Waarschuwingen: &e" + rep.countWarnings(),
                    "&eKlik voor details"),
                    p -> new ValidationDetailGui(plugin, v).open(p));
            idx++;
        }

        button(48, item(Material.CLOCK, "&b&lOpnieuw valideren"),
                p -> new ValidationCenterGui(plugin).open(p));
        button(50, item(Material.WRITABLE_BOOK, "&a&lExporteer rapport",
                "&7Schrijft een TXT + JSON rapport."),
                p -> ValidateCommand.export(plugin, p));

        fillEmpty();
    }
}
