package nl.kmc.core.service;

import nl.kmc.core.domain.TournamentPhase;
import nl.kmc.core.event.RoundEndEvent;
import nl.kmc.core.event.RoundStartEvent;
import nl.kmc.core.event.TournamentEndEvent;
import nl.kmc.core.event.TournamentStartEvent;
import nl.kmc.storage.StorageModule;
import nl.kmc.storage.model.StoredTournamentState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.logging.Logger;

public final class TournamentService {

    private static final Logger LOG = Logger.getLogger(TournamentService.class.getName());

    private final JavaPlugin    plugin;
    private final StorageModule storage;
    private final TeamService   teams;

    // Live state
    private boolean         active;
    private int             currentRound  = 1;
    private int             totalRounds   = 5;
    private int             eventNumber   = 1;
    private TournamentPhase phase         = TournamentPhase.WAITING;
    private String          tournamentName = "KMC";

    // Round multipliers: round index → multiplier
    private Map<Integer, Double> multipliers = Map.of(
            1, 1.0, 2, 2.0, 3, 3.0, 4, 4.0, 5, 5.0);

    public TournamentService(JavaPlugin plugin, StorageModule storage, TeamService teams) {
        this.plugin  = plugin;
        this.storage = storage;
        this.teams   = teams;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();
        tournamentName = cfg.getString("tournament.name", "KMC");
        totalRounds    = cfg.getInt("tournament.total-rounds", 5);

        // Load multipliers from config
        var multSection = cfg.getConfigurationSection("tournament.multipliers");
        if (multSection != null) {
            for (String key : multSection.getKeys(false)) {
                try { multipliers = new java.util.HashMap<>(multipliers);
                      ((java.util.HashMap<Integer, Double>) multipliers)
                              .put(Integer.parseInt(key), multSection.getDouble(key));
                } catch (NumberFormatException ignored) {}
            }
        }

        // Restore persisted state
        storage.tournament().load().thenAccept(opt -> opt.ifPresent(s -> {
            this.active       = s.active;
            this.currentRound = s.currentRound;
            this.eventNumber  = s.eventNumber;
            this.phase        = parsePhaseSafe(s.tournamentPhase);
        })).join();

        // Auto-increment event number on first ever load
        if (eventNumber <= 0) eventNumber = 1;

        LOG.info("[KMC/Tournament] Loaded — event #" + eventNumber + " round=" + currentRound
                 + " active=" + active);
    }

    public void save() {
        StoredTournamentState s = new StoredTournamentState();
        s.active          = active;
        s.currentRound    = currentRound;
        s.eventNumber     = eventNumber;
        s.tournamentPhase = phase.name();
        storage.tournament().save(s);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        if (active) { LOG.warning("[KMC/Tournament] Already active!"); return; }
        active = true;
        eventNumber++;
        currentRound = 1;
        phase = TournamentPhase.OPENING_CEREMONY;
        save();

        plugin.getServer().getPluginManager()
                .callEvent(new TournamentStartEvent(eventNumber, tournamentName));
        plugin.getServer().getPluginManager()
                .callEvent(new RoundStartEvent(currentRound, getMultiplier()));

        LOG.info("[KMC/Tournament] Tournament #" + eventNumber + " started.");
    }

    public void advanceRound() {
        plugin.getServer().getPluginManager()
                .callEvent(new RoundEndEvent(currentRound, teams.getStandings()));

        currentRound++;
        if (currentRound > totalRounds) {
            end();
            return;
        }
        phase = TournamentPhase.TEAM_SHOWCASE;
        save();
        plugin.getServer().getPluginManager()
                .callEvent(new RoundStartEvent(currentRound, getMultiplier()));
    }

    public void end() {
        active = false;
        phase  = TournamentPhase.CLOSING_CEREMONY;
        var topTeam = teams.getStandings().stream().findFirst().orElse(null);
        save();
        plugin.getServer().getPluginManager()
                .callEvent(new TournamentEndEvent(eventNumber, topTeam));
        LOG.info("[KMC/Tournament] Tournament #" + eventNumber + " ended.");
    }

    public void softReset() {
        teams.softResetAll();
        save();
    }

    // ── Getters / setters ─────────────────────────────────────────────────────

    public boolean         isActive()       { return active; }
    public int             getCurrentRound(){ return currentRound; }
    public int             getTotalRounds() { return totalRounds; }
    public int             getEventNumber() { return eventNumber; }
    public TournamentPhase getPhase()       { return phase; }
    public String          getName()        { return tournamentName; }

    public double getMultiplier() {
        return multipliers.getOrDefault(currentRound, 1.0);
    }

    public void setPhase(TournamentPhase phase) {
        this.phase = phase;
        save();
    }

    private TournamentPhase parsePhaseSafe(String name) {
        try { return TournamentPhase.valueOf(name); }
        catch (Exception e) { return TournamentPhase.WAITING; }
    }
}
