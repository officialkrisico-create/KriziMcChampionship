package nl.kmc.kmccore.commands;

import nl.kmc.core.validation.ValidationReport;
import nl.kmc.core.validation.ValidationReport.Status;
import nl.kmc.core.validation.Validator;
import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.gui.ValidationCenterGui;
import nl.kmc.kmccore.validation.CoreValidators;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * /kmcvalidate — the Event Validation System entry point.
 *
 * <pre>
 *   /kmcvalidate          → open the Validation Center GUI
 *   /kmcvalidate test     → run all validators and print a chat report
 *   /kmcvalidate export   → write a TXT + JSON report to disk
 * </pre>
 * Aliases: /kmcreadycheck, /kmceventcheck, /kmcdiagnostics
 */
public final class ValidateCommand implements CommandExecutor {

    private final KMCCore plugin;

    public ValidateCommand(KMCCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command c, String l, String[] args) {
        if (!sender.hasPermission("kmc.admin") && !sender.hasPermission("kmc.tournament.admin")) {
            sender.sendMessage("§cGeen toestemming.");
            return true;
        }

        if (args.length >= 1) {
            switch (args[0].toLowerCase()) {
                case "test"   -> chatReport(sender);
                case "export" -> export(plugin, sender);
                default       -> open(sender);
            }
        } else {
            open(sender);
        }
        return true;
    }

    private void open(CommandSender sender) {
        if (sender instanceof Player p) new ValidationCenterGui(plugin).open(p);
        else chatReport(sender);
    }

    // ── Chat report ─────────────────────────────────────────────────────────────

    private void chatReport(CommandSender sender) {
        List<Validator> validators = CoreValidators.collectAll(plugin);
        int totalScore = 0; long errors = 0, warns = 0;
        sender.sendMessage("§6§l═══ KMC Event Validation ═══");
        for (Validator v : validators) {
            ValidationReport rep = safe(v);
            totalScore += rep.scorePercent();
            errors += rep.countErrors();
            warns  += rep.countWarnings();
            String col = switch (rep.overall()) {
                case READY -> "§a✓"; case PLAYABLE -> "§e⚠"; case NOT_READY -> "§c✗"; };
            sender.sendMessage(col + " §f" + v.displayName() + " §7— " + rep.scorePercent() + "%");
        }
        int overall = validators.isEmpty() ? 100 : totalScore / validators.size();
        sender.sendMessage("§6Totaal: §e" + overall + "% §7(§c" + errors + " fouten§7, §e" + warns + " waarschuwingen§7)");
    }

    // ── Export ──────────────────────────────────────────────────────────────────

    public static void export(KMCCore plugin, CommandSender sender) {
        List<Validator> validators = CoreValidators.collectAll(plugin);
        StringBuilder txt = new StringBuilder("KMC Event Validation Report\n===========================\n\n");
        StringBuilder json = new StringBuilder("{\n  \"validators\": [\n");

        int totalScore = 0;
        for (int i = 0; i < validators.size(); i++) {
            Validator v = validators.get(i);
            ValidationReport rep = safeStatic(v);
            totalScore += rep.scorePercent();
            txt.append("[").append(rep.overall()).append("] ").append(v.displayName())
               .append(" — ").append(rep.scorePercent()).append("%\n");
            json.append("    { \"id\": \"").append(esc(v.id())).append("\", \"name\": \"").append(esc(v.displayName()))
                .append("\", \"status\": \"").append(rep.overall()).append("\", \"score\": ").append(rep.scorePercent())
                .append(", \"checks\": [");
            List<ValidationReport.Check> checks = rep.getChecks();
            for (int j = 0; j < checks.size(); j++) {
                var ch = checks.get(j);
                txt.append("   - ").append(ch.status()).append(" ").append(ch.name()).append(": ").append(ch.message());
                if (ch.fixHint() != null) txt.append("  (fix: ").append(ch.fixHint()).append(")");
                txt.append("\n");
                json.append(j == 0 ? "" : ", ").append("{ \"name\": \"").append(esc(ch.name()))
                    .append("\", \"status\": \"").append(ch.status()).append("\", \"message\": \"").append(esc(ch.message()))
                    .append("\", \"fix\": \"").append(esc(ch.fixHint())).append("\" }");
            }
            json.append("] }").append(i == validators.size() - 1 ? "\n" : ",\n");
            txt.append("\n");
        }
        int overall = validators.isEmpty() ? 100 : totalScore / validators.size();
        txt.insert(0, "Overall readiness: " + overall + "%\n\n");
        json.append("  ],\n  \"overall\": ").append(overall).append("\n}\n");

        try {
            File dir = plugin.getDataFolder();
            Files.writeString(new File(dir, "validation-report.txt").toPath(), txt.toString());
            Files.writeString(new File(dir, "validation-report.json").toPath(), json.toString());
            sender.sendMessage("§a[EVS] Rapport geëxporteerd: §eplugins/KMCCore/validation-report.txt §7+ §e.json");
        } catch (Exception e) {
            sender.sendMessage("§c[EVS] Export mislukt: " + e.getMessage());
        }
    }

    // ── Start protection helper ──────────────────────────────────────────────────

    /** Returns the critical (NOT_READY) issues across all validators — empty if safe to start. */
    public static List<String> criticalIssues(KMCCore plugin) {
        List<String> out = new ArrayList<>();
        for (Validator v : CoreValidators.collectAll(plugin)) {
            ValidationReport rep = safeStatic(v);
            for (ValidationReport.Check ch : rep.getChecks()) {
                if (ch.status() == Status.NOT_READY)
                    out.add(v.displayName() + ": " + ch.message());
            }
        }
        return out;
    }

    private ValidationReport safe(Validator v) { return safeStatic(v); }

    private static ValidationReport safeStatic(Validator v) {
        try { return v.validate(); }
        catch (Throwable t) { ValidationReport r = new ValidationReport(); r.error("Validator", "crash: " + t, null); return r; }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
