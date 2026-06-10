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

        // Per-game validators derived from the SetupService registrations.
        SetupService setup = service(plugin, SetupService.class);
        if (setup != null) {
            for (GameSetup gs : setup.getAll()) all.add(new GameSetupValidator(gs));
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
    public record GameSetupValidator(GameSetup gs) implements Validator {
        @Override public String   id()          { return "game_" + gs.gameId(); }
        @Override public String   displayName() { return gs.displayName(); }
        @Override public Material  icon()        { return gs.icon(); }
        @Override public ValidationReport validate() {
            ValidationReport r = new ValidationReport();
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
