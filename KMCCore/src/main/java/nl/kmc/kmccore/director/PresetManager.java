package nl.kmc.kmccore.director;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Event presets: a named bundle of tournament settings (length, pauses, finale, ready-check, multiplier
 * ladder) that {@code /kmcauto preset apply <name>} writes into config.yml in one go.
 *
 * <p>Presets live under {@code automation.presets.<name>}; the four built-in ones (standard, short,
 * special, test) are added to config.yml the first time they are needed, so an older server config
 * gets them without being overwritten.
 */
public final class PresetManager {

    private static final String ROOT = "automation.presets";

    private final KMCCore plugin;

    public PresetManager(KMCCore plugin) { this.plugin = plugin; }

    // ── Listing ───────────────────────────────────────────────────────────────

    /** Preset names, built-ins first. Adds the built-ins to config.yml when they are missing. */
    public List<String> names() {
        ensureBuiltIns();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(ROOT);
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }

    public boolean exists(String name) {
        ensureBuiltIns();
        return name != null && plugin.getConfig().isConfigurationSection(ROOT + "." + name.toLowerCase());
    }

    /** Human-readable lines describing a preset (for /kmcauto preset show). */
    public List<String> describe(String name) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(ROOT + "." + name.toLowerCase());
        if (s == null) return List.of();
        int games = s.getInt("total-games", 0);
        int gpr = Math.max(1, s.getInt("games-per-round", 2));
        List<String> out = new ArrayList<>();
        out.add("&6" + name.toLowerCase() + " &7— " + s.getString("description", ""));
        out.add("&7Games: &e" + games + " &7(" + gpr + " per ronde → &e" + roundsFor(games, gpr) + " &7rondes)");
        out.add("&7Pauze: &e" + s.getInt("intermission-seconds", 30) + "s &7| Stemmen: &e"
                + s.getInt("voting-duration", 30) + "s &7| Halverwege-pauze: "
                + (s.getBoolean("mid-break.enabled", true) ? "&e" + s.getInt("mid-break.seconds", 300) + "s" : "&7uit"));
        out.add("&7Finale: " + (s.getBoolean("finale", true) ? "&aaan" : "&7uit")
                + " &7| Ready-check: &e" + s.getString("ready-check", "mid-break"));
        List<Double> mult = s.getDoubleList("multipliers");
        if (!mult.isEmpty()) out.add("&7Multipliers: &e" + mult);
        var est = TimeBudget.compute(TimeBudget.fromPlugin(plugin, games, gpr));
        out.add("&7Geschatte duur: &e" + est.format());
        return out;
    }

    // ── Applying ──────────────────────────────────────────────────────────────

    /**
     * Writes the preset into the live configuration.
     *
     * @param report receives one line per notable change / warning
     * @return false if the preset does not exist
     */
    public boolean apply(String name, List<String> report) {
        if (!exists(name)) return false;
        var cfg = plugin.getConfig();
        ConfigurationSection s = cfg.getConfigurationSection(ROOT + "." + name.toLowerCase());
        if (s == null) return false;

        int gpr = Math.max(1, s.getInt("games-per-round", cfg.getInt("automation.games-per-round", 2)));
        int games = Math.max(1, s.getInt("total-games", 8));
        int available = plugin.getGameManager().getEnabledGames().size();
        if (available > 0 && games > available) {
            report.add("&eLet op: preset vraagt " + games + " games, maar er zijn er maar " + available
                    + " ingeschakeld — er worden " + available + " gespeeld.");
            games = available;
        }

        cfg.set("automation.games-per-round", gpr);
        cfg.set("automation.total-games", games);
        cfg.set("automation.intermission-seconds", s.getInt("intermission-seconds", 30));
        cfg.set("games.voting-duration", s.getInt("voting-duration", 30));
        cfg.set("automation.mid-break.enabled", s.getBoolean("mid-break.enabled", true));
        cfg.set("automation.mid-break.seconds", s.getInt("mid-break.seconds", 300));
        cfg.set("automation.finale.enabled", s.getBoolean("finale", true));
        cfg.set("automation.ready-check.mode", s.getString("ready-check", "mid-break"));

        int rounds = roundsFor(games, gpr);
        List<Double> ladder = s.getDoubleList("multipliers");
        if (!ladder.isEmpty()) {
            cfg.set("tournament.multipliers", null);
            for (int r = 1; r <= rounds; r++) {
                double m = ladder.get(Math.min(r - 1, ladder.size() - 1));
                cfg.set("tournament.multipliers." + r, m);
            }
        }
        plugin.getTournamentManager().setTotalRounds(rounds);   // saves config
        report.add("&aPreset &e" + name.toLowerCase() + "&a toegepast: &e" + games + " &agames in &e"
                + rounds + " &arondes.");
        return true;
    }

    /** Applies just a new number of games (the setup menu): rounds follow from games-per-round. */
    public int applyGameCount(int games) {
        var cfg = plugin.getConfig();
        int gpr = Math.max(1, cfg.getInt("automation.games-per-round", 2));
        games = Math.max(1, games);
        cfg.set("automation.total-games", games);
        int rounds = roundsFor(games, gpr);
        // Extend the multiplier ladder if the tournament got longer than the configured steps.
        double last = 1.0;
        for (int r = 1; r <= rounds; r++) {
            String key = "tournament.multipliers." + r;
            if (cfg.isSet(key)) last = cfg.getDouble(key, last);
            else cfg.set(key, last);
        }
        plugin.getTournamentManager().setTotalRounds(rounds);
        return rounds;
    }

    /** Stores the current settings as a preset (overwrites). */
    public void saveCurrent(String name) {
        var cfg = plugin.getConfig();
        String base = ROOT + "." + name.toLowerCase();
        int gpr = Math.max(1, cfg.getInt("automation.games-per-round", 2));
        int rounds = plugin.getTournamentManager().getTotalRounds();
        cfg.set(base + ".description", "Opgeslagen via /kmcauto preset save");
        cfg.set(base + ".total-games", cfg.getInt("automation.total-games", rounds * gpr));
        cfg.set(base + ".games-per-round", gpr);
        cfg.set(base + ".intermission-seconds", cfg.getInt("automation.intermission-seconds", 30));
        cfg.set(base + ".voting-duration", cfg.getInt("games.voting-duration", 30));
        cfg.set(base + ".mid-break.enabled", cfg.getBoolean("automation.mid-break.enabled", true));
        cfg.set(base + ".mid-break.seconds", cfg.getInt("automation.mid-break.seconds", 300));
        cfg.set(base + ".finale", cfg.getBoolean("automation.finale.enabled", true));
        cfg.set(base + ".ready-check", cfg.getString("automation.ready-check.mode", "mid-break"));
        List<Double> ladder = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) ladder.add(cfg.getDouble("tournament.multipliers." + r, 1.0));
        cfg.set(base + ".multipliers", ladder);
        plugin.saveConfig();
    }

    public static int roundsFor(int games, int gamesPerRound) {
        return (int) Math.ceil(Math.max(1, games) / (double) Math.max(1, gamesPerRound));
    }

    // ── Built-ins ─────────────────────────────────────────────────────────────

    private void ensureBuiltIns() {
        var cfg = plugin.getConfig();
        boolean changed = false;
        changed |= builtIn("standard", "Standaard KMC-avond: 12 games met finale",
                12, 2, 30, 30, true, 300, true, "mid-break", List.of(1.0, 1.5, 2.0, 2.5, 3.0, 4.0));
        changed |= builtIn("short", "Kort event (± 1,5 uur): 6 games met finale",
                6, 2, 20, 25, false, 0, true, "mid-break", List.of(1.0, 1.5, 2.5));
        changed |= builtIn("special", "Speciale editie: 16 games, lange pauze en finale",
                16, 2, 30, 30, true, 420, true, "every-break",
                List.of(1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 5.0));
        changed |= builtIn("test", "Testavond: 2 games, korte timers, geen finale of ready-check",
                2, 2, 5, 10, false, 0, false, "off", List.of(1.0));
        if (changed) plugin.saveConfig();
    }

    private boolean builtIn(String name, String desc, int games, int gpr, int intermission, int vote,
                            boolean midBreak, int midBreakSeconds, boolean finale, String ready, List<Double> ladder) {
        var cfg = plugin.getConfig();
        String base = ROOT + "." + name;
        if (cfg.isConfigurationSection(base)) return false;
        cfg.set(base + ".description", desc);
        cfg.set(base + ".total-games", games);
        cfg.set(base + ".games-per-round", gpr);
        cfg.set(base + ".intermission-seconds", intermission);
        cfg.set(base + ".voting-duration", vote);
        cfg.set(base + ".mid-break.enabled", midBreak);
        cfg.set(base + ".mid-break.seconds", midBreakSeconds);
        cfg.set(base + ".finale", finale);
        cfg.set(base + ".ready-check", ready);
        cfg.set(base + ".multipliers", ladder);
        return true;
    }
}
