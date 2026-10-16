package nl.kmc.kmccore.validation;

import nl.kmc.kmccore.KMCCore;
import nl.kmc.kmccore.host.HostValues;
import nl.kmc.kmccore.validation.ConfigDoctor.Finding;
import nl.kmc.kmccore.validation.ConfigDoctor.Severity;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs {@link ConfigDoctor} over the config files of KMCCore and of every loaded KMC game plugin, plus
 * the checks that need to know the tournament (rounds against multipliers, games that are not loaded).
 * Read-only: it never edits a file.
 */
public final class ConfigDoctorRun {

    private ConfigDoctorRun() { }

    public record Result(List<Finding> findings, List<String> files) { }

    /** Config sections that are keyed by an id the admin chooses. */
    private static final Set<String> FREE_FORM = Set.of("games.list", "teams", "tournament.multipliers");

    private static final List<String> CORE_FILES = List.of("config.yml", "host.yml", "points.yml", "ceremonies.yml");

    public static Result run(KMCCore plugin) {
        List<Finding> out = new ArrayList<>();
        List<String> files = new ArrayList<>();

        for (String name : CORE_FILES) {
            checkFile(plugin, "KMCCore", name, FREE_FORM, out, files);
        }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p == plugin || !isKmcGame(p)) continue;
            checkFile(p, p.getName(), "config.yml", Set.of(), out, files);
        }
        semantic(plugin, out);
        try {
            var host = plugin.getHost();
            if (host != null) for (String w : host.contentWarnings())
                out.add(new Finding("KMCCore/host.yml", "", Severity.WARN, w, "pas host.yml aan"));
        } catch (Throwable ignored) { }
        return new Result(out, files);
    }

    private static boolean isKmcGame(Plugin p) {
        var d = p.getDescription();
        return d.getDepend().contains("KMCCore") || d.getSoftDepend().contains("KMCCore")
                || d.getDepend().contains("kmc-game-api") || d.getSoftDepend().contains("kmc-game-api");
    }

    private static void checkFile(Plugin owner, String label, String resource, Set<String> freeForm,
                                  List<Finding> out, List<String> files) {
        String fileLabel = label + "/" + resource;
        File f = new File(owner.getDataFolder(), resource);
        if (!f.isFile()) return;
        YamlConfiguration actual = new YamlConfiguration();
        try {
            actual.load(f);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            out.add(new Finding(fileLabel, "", Severity.ERROR, "het bestand is geen geldige YAML: " + msg,
                    "controleer inspringing en aanhalingstekens"));
            files.add(fileLabel);
            return;
        }
        YamlConfiguration defaults = null;
        try (InputStream in = owner.getResource(resource)) {
            if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        files.add(fileLabel);
        if (defaults == null) return;                         // no shipped copy to compare with
        out.addAll(ConfigDoctor.compare(fileLabel, defaults, actual, freeForm,
                n -> Material.matchMaterial(n) != null));
    }

    // ── Tournament-aware checks ───────────────────────────────────────────────

    private static void semantic(KMCCore plugin, List<Finding> out) {
        var cfg = plugin.getConfig();
        String file = "KMCCore/config.yml";

        int rounds = cfg.getInt("tournament.total-rounds", 0);
        ConfigurationSection mult = cfg.getConfigurationSection("tournament.multipliers");
        if (rounds > 0 && mult != null) {
            List<String> missing = new ArrayList<>();
            for (int r = 1; r <= rounds; r++) if (!mult.contains(String.valueOf(r))) missing.add(String.valueOf(r));
            if (!missing.isEmpty())
                out.add(new Finding(file, "tournament.multipliers", Severity.WARN,
                        "geen multiplier voor ronde " + String.join(", ", missing)
                                + " (tournament.total-rounds is " + rounds + ")",
                        "voeg die rondes toe, of verlaag total-rounds"));
        }
        if (cfg.isSet("automation.mid-break.after-round") && rounds > 0) {
            int after = cfg.getInt("automation.mid-break.after-round");
            if (after < 1 || after >= rounds)
                out.add(new Finding(file, "automation.mid-break.after-round", Severity.WARN,
                        "ronde " + after + " ligt niet binnen 1.." + (rounds - 1) + ": de pauze komt nooit",
                        "kies een ronde voor het einde van het toernooi"));
        }

        // games that are listed but have no plugin behind them
        ConfigurationSection list = cfg.getConfigurationSection("games.list");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                if (!list.getBoolean(id + ".enabled", true)) continue;
                if (HostValues.registration(id).isEmpty())
                    out.add(new Finding(file, "games.list." + id, Severity.WARN,
                            "geen game-plugin geladen voor dit id",
                            "jar ontbreekt in plugins/ of het id is verkeerd gespeld"));
            }
        }
        String finale = cfg.getString("automation.finale.game", "");
        if (cfg.getBoolean("automation.finale.enabled", true) && !finale.isBlank() && HostValues.registration(finale).isEmpty())
            out.add(new Finding(file, "automation.finale.game", Severity.ERROR,
                    "de finale-game '" + finale + "' is niet geladen",
                    "zet het juiste game-id of kopieer de jar"));

        double golden = cfg.getDouble("golden-hour.multiplier", 2.0);
        if (cfg.getBoolean("golden-hour.enabled", true) && golden < 1.0)
            out.add(new Finding(file, "golden-hour.multiplier", Severity.WARN,
                    "een golden hour met " + golden + "x verlaagt de punten", "gebruik 1.0 of hoger"));

        // host settings that only make sense together
        int close = cfg.getInt("host.leaderboard-announcements.close-gap", 150);
        int runaway = cfg.getInt("host.leaderboard-announcements.runaway-gap", 500);
        if (close >= runaway)
            out.add(new Finding(file, "host.leaderboard-announcements", Severity.WARN,
                    "close-gap (" + close + ") is niet kleiner dan runaway-gap (" + runaway + ")",
                    "close-gap moet kleiner zijn"));
        double minDur = cfg.getDouble("host.dialogue.default-duration", 3.0);
        double maxDur = cfg.getDouble("host.dialogue.max-duration", 9.0);
        if (minDur > maxDur)
            out.add(new Finding(file, "host.dialogue.default-duration", Severity.ERROR,
                    "default-duration (" + minDur + ") is groter dan max-duration (" + maxDur + ")", "wissel ze om"));
        double h = cfg.getDouble("host.npc.dialogue-height", 2.6);
        if (h < 0.5 || h > 6.0)
            out.add(new Finding(file, "host.npc.dialogue-height", Severity.WARN,
                    "hoogte " + h + " valt buiten 0.5..6 en wordt begrensd", ""));
    }

    // ── Plain-text report ─────────────────────────────────────────────────────

    public static List<String> lines(Result r) {
        List<String> out = new ArrayList<>();
        Set<String> filesWithFindings = new LinkedHashSet<>();
        for (Finding f : r.findings()) filesWithFindings.add(f.file());
        for (String file : r.files()) {
            if (!filesWithFindings.contains(file)) out.add("OK    " + file);
        }
        for (String file : filesWithFindings) {
            out.add("---   " + file);
            for (Finding f : r.findings()) {
                if (!f.file().equals(file)) continue;
                out.add("  " + f.severity() + "  " + (f.path().isEmpty() ? "" : f.path() + ": ") + f.message()
                        + (f.hint() == null || f.hint().isBlank() ? "" : "  (" + f.hint() + ")"));
            }
        }
        return out;
    }
}
