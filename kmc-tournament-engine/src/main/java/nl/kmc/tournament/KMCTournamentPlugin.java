package nl.kmc.tournament;

import nl.kmc.core.KMCCorePlugin;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.core.service.TeamService;
import nl.kmc.core.service.TournamentService;
import nl.kmc.tournament.command.*;
import nl.kmc.tournament.engine.AutomationEngine;
import nl.kmc.tournament.engine.TournamentEngine;
import nl.kmc.tournament.recovery.RecoveryScheduler;
import nl.kmc.tournament.recovery.TournamentRecoveryEngine;
import nl.kmc.tournament.reconnect.ReconnectManager;
import nl.kmc.tournament.template.TemplateManager;
import nl.kmc.tournament.timeline.TimelineDisplayManager;
import nl.kmc.tournament.voting.VotingEngine;
import nl.kmc.core.api.KMCApiProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Main plugin class for the KMC Tournament Engine.
 *
 * Wires all subsystems in dependency order and registers commands.
 * Requires KMCCore to be enabled first (soft-dep via plugin.yml).
 */
public final class KMCTournamentPlugin extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger(KMCTournamentPlugin.class.getName());

    // ── Subsystems ─────────────────────────────────────────────────────────────

    private TournamentEngine         tournamentEngine;
    private VotingEngine             votingEngine;
    private TournamentRecoveryEngine recoveryEngine;
    private RecoveryScheduler        recoveryScheduler;
    private TemplateManager          templateManager;
    private TimelineDisplayManager   timelineDisplay;
    private ReconnectManager         reconnectManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ── Acquire services from KMCCore ────────────────────────────────────
        KMCCorePlugin core = (KMCCorePlugin) getServer().getPluginManager().getPlugin("KMCCore");
        if (core == null || !core.isEnabled()) {
            LOG.severe("[KMC/Engine] KMCCore is not loaded — disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        var api          = KMCApiProvider.get();
        // Retrieve concrete services via ServiceContainer (KMCCore exposes it)
        TournamentService   tournamentService = core.getContainer().get(TournamentService.class);
        TeamService         teamService       = core.getContainer().get(TeamService.class);
        GameRegistryService registryService   = core.getContainer().get(GameRegistryService.class);

        // ── Build engine subsystems ──────────────────────────────────────────
        recoveryEngine    = new TournamentRecoveryEngine(tournamentService, teamService, registryService);
        tournamentEngine  = new TournamentEngine(this, tournamentService, teamService,
                                                 registryService, recoveryEngine);
        votingEngine      = new VotingEngine(this);
        templateManager   = new TemplateManager(getDataFolder());
        timelineDisplay   = new TimelineDisplayManager(this, teamService);
        reconnectManager  = new ReconnectManager(this,
                getConfig().getInt("reconnect.timeout-seconds", 120));
        recoveryScheduler = new RecoveryScheduler(this, recoveryEngine);

        // ── Register Bukkit listeners ────────────────────────────────────────
        getServer().getPluginManager().registerEvents(tournamentEngine, this);

        // ── Register phase handlers ──────────────────────────────────────────
        new AutomationEngine(this, tournamentEngine, votingEngine, registryService).registerAll();

        // ── Load templates ───────────────────────────────────────────────────
        templateManager.loadAll();

        // ── Register commands ────────────────────────────────────────────────
        TournamentCommand kmcCmd = new TournamentCommand(
                tournamentEngine, votingEngine, recoveryEngine, templateManager);
        Objects.requireNonNull(getCommand("kmc")).setExecutor(kmcCmd);
        Objects.requireNonNull(getCommand("kmc")).setTabCompleter(kmcCmd);

        TimelineCommand tlCmd = new TimelineCommand(tournamentService, registryService, timelineDisplay);
        Objects.requireNonNull(getCommand("kmctimeline")).setExecutor(tlCmd);

        TemplateCommand tplCmd = new TemplateCommand(templateManager);
        Objects.requireNonNull(getCommand("kmctemplate")).setExecutor(tplCmd);
        Objects.requireNonNull(getCommand("kmctemplate")).setTabCompleter(tplCmd);

        RecoverCommand recCmd = new RecoverCommand(tournamentEngine, recoveryEngine);
        Objects.requireNonNull(getCommand("kmcrecover")).setExecutor(recCmd);

        // ── Start recovery scheduler if a tournament is already active ────────
        if (tournamentService.isActive()) {
            int intervalSec = getConfig().getInt("recovery.snapshot-interval", 60);
            recoveryScheduler.start(intervalSec);
            LOG.info("[KMC/Engine] Active tournament found — recovery scheduler started.");
        }

        LOG.info("[KMC/Engine] KMC Tournament Engine enabled.");
    }

    @Override
    public void onDisable() {
        if (recoveryScheduler != null) recoveryScheduler.stop();
        if (reconnectManager  != null) reconnectManager.clearAll();
        LOG.info("[KMC/Engine] KMC Tournament Engine disabled.");
    }

    // ── Getters for inter-plugin access ──────────────────────────────────────

    public TournamentEngine         getTournamentEngine()  { return tournamentEngine; }
    public VotingEngine             getVotingEngine()      { return votingEngine; }
    public TournamentRecoveryEngine getRecoveryEngine()    { return recoveryEngine; }
    public ReconnectManager         getReconnectManager()  { return reconnectManager; }
    public TemplateManager          getTemplateManager()   { return templateManager; }
}
