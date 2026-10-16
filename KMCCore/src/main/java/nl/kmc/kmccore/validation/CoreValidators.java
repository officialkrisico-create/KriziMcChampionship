package nl.kmc.kmccore.validation;

import nl.kmc.core.setup.GameSetup;
import nl.kmc.core.setup.SetupService;
import nl.kmc.core.validation.ValidationManager;
import nl.kmc.core.validation.ValidationReport;
import nl.kmc.core.validation.Validator;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in validators for the Event Validation System, plus the aggregator that
 * collects every validator (built-ins + per-game + externally registered) for
 * the Validation Center.
 */
public final class CoreValidators {

    private CoreValidators() {}

    /** Every validator the EVS should run, freshly built. */
    public static List<Validator> collectAll(KMCCore plugin) {
        List<Validator> all = new ArrayList<>();
        all.add(new TournamentValidator(plugin));
        all.add(new PresentationValidator(plugin));
        all.add(new FlyoverValidator(plugin));
        all.add(new NpcValidator(plugin));
        all.add(new AchievementValidator(plugin));
        all.add(new DatabaseValidator(plugin));
        all.add(new ConfigDoctorValidator(plugin));

        // Per-game validators derived from the SetupService registrations.
        SetupService setup = service(plugin, SetupService.class);
        if (setup != null) {
            for (GameSetup gs : setup.getAll()) {
                // A game switched off in /kmcauto's setup menu is not part of this tournament, so its missing
                // arena must not block the start (it is still listed — as "disabled", not as an error).
                boolean enabled = plugin.getConfig().getBoolean("games.list." + gs.gameId() + ".enabled", true);
                all.add(new GameSetupValidator(gs, enabled));
            }
        }

        // Any externally / future registered validators.
        ValidationManager vm = service(plugin, ValidationManager.class);
        if (vm != null) all.addAll(vm.getValidators());

        return all;
    }

    private static <T> T service(KMCCore plugin, Class<T> type) {
        if (Bukkit.getPluginManager().getPlugin("KMCCoreV2") instanceof nl.kmc.core.KMCCorePlugin v2) {
            return v2.getContainer().get(type);
        }
        return null;
    }

    /** Wraps each game's registered setup into a validator (reuses real arena validation). */
    public record GameSetupValidator(GameSetup gs, boolean enabled) implements Validator {
        @Override public String   id()          { return "game_" + gs.gameId(); }
        @Override public String   displayName() { return gs.displayName(); }
        @Override public Material  icon()        { return gs.icon(); }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            if (!enabled) {
                r.ok("Rotatie", "Uitgeschakeld in /kmcauto — wordt niet gespeeld");
                return r;
            }
            List<String> issues;
            try { issues = gs.issues(); } catch (Throwable t) { issues = List.of("Validatie-fout: " + t); }
            if (issues.isEmpty()) {
                r.ok("Arena", "Klaar om te spelen");
            } else {
                for (String issue : issues) r.error("Configuratie", issue, "/kmcsetup → " + gs.displayName());
            }
            return r;
        }
    }

    // ── Configuration (the config doctor) ──────────────────────────────────────

    /**
     * Typos, wrong types and impossible values in the config files of KMCCore and the game plugins.
     * Most findings are warnings (the game still plays); only an unreadable file or a missing finale game
     * blocks.
     */
    public record ConfigDoctorValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "config"; }
        @Override public String  displayName() { return "Configuratie"; }
        @Override public Material icon()        { return Material.WRITABLE_BOOK; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            ConfigDoctorRun.Result res;
            try { res = ConfigDoctorRun.run(plugin); }
            catch (Throwable t) { r.warn("Config-doctor", "kon niet draaien: " + t, "/kmcvalidate config"); return r; }
            java.util.Map<String, java.util.List<ConfigDoctor.Finding>> byFile = new java.util.LinkedHashMap<>();
            for (var f : res.findings()) byFile.computeIfAbsent(f.file(), k -> new java.util.ArrayList<>()).add(f);
            for (String file : res.files()) if (!byFile.containsKey(file)) r.ok(file, "geen afwijkingen");
            for (var en : byFile.entrySet()) {
                int shown = 0;
                for (var f : en.getValue()) {
                    if (shown++ >= 6) {
                        r.warn(en.getKey(), "... en " + (en.getValue().size() - 6) + " meer", "/kmcvalidate config");
                        break;
                    }
                    String text = (f.path().isEmpty() ? "" : f.path() + ": ") + f.message();
                    boolean blocks = f.severity() == ConfigDoctor.Severity.ERROR
                            && (f.path().isEmpty() || f.path().equals("automation.finale.game"));
                    if (blocks) r.error(en.getKey(), text, f.hint());
                    else r.warn(en.getKey(), text, f.hint());
                }
            }
            return r;
        }
    }

    // ── Tournament ─────────────────────────────────────────────────────────────


    public record TournamentValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "tournament"; }
        @Override public String  displayName() { return "Toernooi"; }
        @Override public Material icon()        { return Material.GOLDEN_APPLE; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            var cfg = plugin.getConfig();

            if (plugin.getArenaManager().getLobby() != null) r.ok("Lobby", "Ingesteld");
            else r.error("Lobby", "Geen lobby ingesteld", "/kmcsetup → Lobby");

            int teams = plugin.getTeamManager().getAllTeams().size();
            if (teams == 0)      r.error("Teams", "Geen teams", "/kmcteam create ...");
            else if (teams < 2)  r.warn("Teams", teams + " team (minimaal 2 aanbevolen)", "/kmcteam create ...");
            else                 r.ok("Teams", teams + " teams");

            int rounds = cfg.getInt("tournament.total-rounds", 0);
            if (rounds > 0) r.ok("Rondes", rounds + " rondes");
            else r.error("Rondes", "tournament.total-rounds niet ingesteld", "config.yml");

            if (cfg.isConfigurationSection("tournament.multipliers")) r.ok("Multipliers", "Geconfigureerd");
            else r.warn("Multipliers", "Geen round-multipliers", "config.yml: tournament.multipliers");

            r.ok("Stemmen", cfg.getBoolean("games.voting-enabled", true) ? "AAN" : "UIT");

            // ── Event checklist (director) ─────────────────────────────────────
            var enabled = plugin.getGameManager().getEnabledGames();
            int totalGames = plugin.getAutomationManager().getTotalGames();
            if (totalGames > enabled.size())
                r.warn("Aantal games", totalGames + " games gepland, maar maar " + enabled.size()
                        + " in de rotatie — games komen dubbel voor", "/kmcauto games " + enabled.size());
            else r.ok("Aantal games", totalGames + " van " + enabled.size() + " games in de rotatie");

            for (var team : plugin.getTeamManager().getAllTeams()) {
                int size = team.getMembers().size();
                if (size == 0) r.warn("Team " + team.getDisplayName(), "Geen spelers", "/kmcteam add");
                else if (size > 4) r.warn("Team " + team.getDisplayName(), size + " spelers (max. 4 aanbevolen)", "/kmcteam remove");
            }

            if (cfg.getBoolean("automation.finale.enabled", true)) {
                String fg = cfg.getString("automation.finale.game", "dodgebolt");
                var fgame = plugin.getGameManager().getGame(fg);
                // Not an error: without a ready finale game the tournament simply ends on points.
                if (fgame == null) r.warn("Finale", "Finale-game '" + fg + "' bestaat niet — toernooi eindigt op punten", "automation.finale.game");
                else if (plugin.getAutomationManager().isGameReady(fg)) r.ok("Finale", fgame.getDisplayName() + " klaar");
                else r.warn("Finale", fgame.getDisplayName() + " is niet geconfigureerd — toernooi eindigt op punten",
                        "/kmcsetup → " + fgame.getDisplayName() + " of /kmcauto finale off");
            } else r.ok("Finale", "Uitgeschakeld");

            int untested = 0;
            for (var g : enabled) {
                if (!plugin.getDirector().isTested(g.getId())) untested++;
            }
            if (untested > 0)
                r.warn("Dry-run", untested + " game(s) nog niet getest met een dry-run", "/kmcauto dryrun <game>");
            else r.ok("Dry-run", "Alle games getest");

            var est = nl.kmc.kmccore.director.TimeBudget.forCurrent(plugin, totalGames);
            r.ok("Geschatte duur", est.format() + " (" + est.rounds() + " rondes)");
            return r;
        }
    }

    // ── Presentation (camera routes for ceremonies) ───────────────────────────

    public record PresentationValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "presentation"; }
        @Override public String  displayName() { return "Presentatie"; }
        @Override public Material icon()        { return Material.FILLED_MAP; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            var cm = plugin.getCinematicManager();
            // Ceremony routes are optional — missing ones are warnings, not errors.
            for (String route : new String[]{"opening", "team-showcase", "tournament-overview", "closing"}) {
                if (cm.routeExists(route)) r.ok("Route: " + route, "Aanwezig");
                else r.warn("Route: " + route, "Niet ingesteld (optioneel)", "/kmccamera create " + route);
            }
            // Corrupt route check.
            cm.getAllRoutes().forEach(rt -> {
                if (rt.size() < 2) r.warn("Route: " + rt.getId(), "Te weinig waypoints (" + rt.size() + ")",
                        "/kmccamera addpoint");
            });
            return r;
        }
    }

    // ── Flyovers (per-game cinematic flyover routes) ──────────────────────────

    public record FlyoverValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "flyover"; }
        @Override public String  displayName() { return "Flyovers"; }
        @Override public Material icon()        { return Material.ELYTRA; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            var cm = plugin.getCinematicManager();
            int min = plugin.getConfig().getInt("flyover.min-points", 2);
            SetupService setup = service(plugin, SetupService.class);
            if (setup == null || setup.getAll().isEmpty()) {
                r.warn("Games", "Geen games geregistreerd", null);
                return r;
            }
            for (GameSetup gs : setup.getAll()) {
                String route = "arena-" + gs.gameId();
                var opt = cm.getRoute(route);
                if (opt.isEmpty())
                    r.warn(gs.displayName(), "Geen flyover (optioneel)", "/kmcsetup → Flyovers");
                else if (opt.get().size() < min)
                    r.warn(gs.displayName(), "Te weinig camerapunten (" + opt.get().size() + "/" + min + ")", "/kmcsetup → Flyovers");
                else
                    r.ok(gs.displayName(), "✔ " + opt.get().size() + " camerapunten");
            }
            return r;
        }
    }

    // ── NPCs ───────────────────────────────────────────────────────────────────

    public record NpcValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "npc"; }
        @Override public String  displayName() { return "NPCs"; }
        @Override public Material icon()        { return Material.PLAYER_HEAD; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            boolean fancy = Bukkit.getPluginManager().getPlugin("FancyNpcs") != null;
            if (fancy) r.ok("FancyNpcs", "Beschikbaar");
            else r.warn("FancyNpcs", "Niet geïnstalleerd — NPC-functies uit", "Installeer FancyNpcs");
            return r;
        }
    }

    // ── Achievements ───────────────────────────────────────────────────────────

    public record AchievementValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "achievements"; }
        @Override public String  displayName() { return "Achievements"; }
        @Override public Material icon()        { return Material.EXPERIENCE_BOTTLE; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            String sys = plugin.getConfig().getString("settings.achievement-system", "v1");
            if (sys.equalsIgnoreCase("v2")) {
                var svc = plugin.getAchievementServiceV2();
                if (svc == null) r.error("V2 service", "achievement-system=v2 maar service ontbreekt", "Installeer KMCCoreV2");
                else if (svc.getAll().isEmpty()) r.warn("Definities", "Geen achievements geladen", "achievements.yml");
                else r.ok("V2 systeem", svc.getAll().size() + " achievements");
            } else {
                r.ok("Systeem", "V1 (legacy) actief");
            }
            return r;
        }
    }

    // ── Database ───────────────────────────────────────────────────────────────

    public record DatabaseValidator(KMCCore plugin) implements Validator {
        @Override public String  id()          { return "database"; }
        @Override public String  displayName() { return "Database"; }
        @Override public Material icon()        { return Material.CHEST; }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
            try {
                plugin.getDatabaseManager().getTournamentValue("active", "false");
                r.ok("Verbinding", "Database bereikbaar");
            } catch (Throwable t) {
                r.error("Verbinding", "Database-fout: " + t.getMessage(), "Controleer de database");
            }
            return r;
        }
    }
}
