package nl.kmc.tournament.engine;

import nl.kmc.core.domain.TournamentPhase;
import nl.kmc.core.service.GameRegistryService;
import nl.kmc.tournament.voting.VotingEngine;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Registers concrete {@link TournamentPhaseHandler} implementations for every phase.
 * Each handler receives the engine reference and calls {@link TournamentEngine#advance()}
 * when its sequence is complete — either via timer or explicit signal.
 */
public final class AutomationEngine {

    private static final Logger LOG = Logger.getLogger(AutomationEngine.class.getName());

    private final JavaPlugin         plugin;
    private final TournamentEngine   engine;
    private final VotingEngine       voting;
    private final GameRegistryService registry;

    public AutomationEngine(JavaPlugin plugin, TournamentEngine engine,
                            VotingEngine voting, GameRegistryService registry) {
        this.plugin   = plugin;
        this.engine   = engine;
        this.voting   = voting;
        this.registry = registry;
    }

    /** Register all handlers with the engine. Call once at startup. */
    public void registerAll() {
        engine.registerHandler(readyCheckHandler());
        engine.registerHandler(openingCeremonyHandler());
        engine.registerHandler(teamShowcaseHandler());
        engine.registerHandler(tournamentOverviewHandler());
        engine.registerHandler(gameLineupShowcaseHandler());
        engine.registerHandler(votingPhaseHandler());
        engine.registerHandler(gameIntroHandler());
        engine.registerHandler(gameActiveHandler());
        engine.registerHandler(gameEndCeremonyHandler());
        engine.registerHandler(roundEndCeremonyHandler());
        engine.registerHandler(closingCeremonyHandler());
        engine.registerHandler(endedHandler());
        LOG.info("[KMC/Automation] All phase handlers registered.");
    }

    // ── Phase handlers ────────────────────────────────────────────────────────

    private TournamentPhaseHandler readyCheckHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.READY_CHECK; }
            public void enter(TournamentEngine e) {
                // ReadyUpService broadcasts — when all players confirm, call advance()
                // For now: auto-advance after configurable delay if no ready-up system active
                int delaySec = plugin.getConfig().getInt("automation.ready-check-timeout", 30);
                boolean skipReadyCheck = plugin.getConfig().getBoolean("automation.skip-ready-check", false);
                if (skipReadyCheck) { e.advance(); return; }
                plugin.getServer().broadcastMessage("§e§l[KMC] Ready check — type /kmc ready to confirm!");
                schedule(delaySec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler openingCeremonyHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.OPENING_CEREMONY; }
            public void enter(TournamentEngine e) {
                // PresentationEngine handles visuals — it fires advance() when done
                // Fallback timer if presentation engine absent
                int durationSec = plugin.getConfig().getInt("automation.ceremony.opening-duration", 20);
                broadcastPhase("§6§l▸ OPENING CEREMONY ◂");
                schedule(durationSec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler teamShowcaseHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.TEAM_SHOWCASE; }
            public void enter(TournamentEngine e) {
                int teams     = e.getTeams().getAllTeams().size();
                int perTeamSec = plugin.getConfig().getInt("automation.ceremony.team-reveal-seconds", 4);
                broadcastPhase("§b§l▸ TEAM SHOWCASE ◂");
                schedule(teams * perTeamSec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler tournamentOverviewHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.TOURNAMENT_OVERVIEW; }
            public void enter(TournamentEngine e) {
                int durationSec = plugin.getConfig().getInt("automation.ceremony.overview-duration", 12);
                broadcastPhase("§d§l▸ TOURNAMENT OVERVIEW ◂");
                schedule(durationSec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler gameLineupShowcaseHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.GAME_LINEUP_SHOWCASE; }
            public void enter(TournamentEngine e) {
                int games = e.getRegistry().getUnplayed().size();
                int perGameSec = plugin.getConfig().getInt("automation.ceremony.lineup-per-game-seconds", 3);
                broadcastPhase("§a§l▸ GAME LINEUP ◂");
                e.getRegistry().getUnplayed().forEach(g ->
                        plugin.getServer().broadcastMessage("§7  » §e" + g.getDisplayName()));
                schedule(Math.max(5, games * perGameSec), () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler votingPhaseHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.VOTING_PHASE; }
            public void enter(TournamentEngine e) {
                int durationSec = plugin.getConfig().getInt("automation.voting.duration", 30);
                broadcastPhase("§6§l▸ VOTE FOR THE NEXT GAME ◂");
                voting.startVote(e.getRegistry().getUnplayed(), durationSec, result -> {
                    // Mark selected game as active
                    result.ifPresent(reg -> e.getRegistry().setActive(reg.getId()));
                    e.advance();
                });
            }
            public void skip(TournamentEngine e) {
                voting.forceEnd();
                e.advance();
            }
        };
    }

    private TournamentPhaseHandler gameIntroHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.GAME_INTRO; }
            public void enter(TournamentEngine e) {
                int durationSec = plugin.getConfig().getInt("automation.ceremony.game-intro-duration", 15);
                e.getRegistry().getActive().ifPresent(reg -> {
                    broadcastPhase("§e§l▸ " + reg.getDisplayName().toUpperCase() + " ◂");
                    plugin.getServer().broadcastMessage("§7  Objective: §f" + reg.getObjective());
                });
                schedule(durationSec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler gameActiveHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.GAME_ACTIVE; }
            public void enter(TournamentEngine e) {
                e.getRegistry().getActive().ifPresent(reg -> {
                    LOG.info("[KMC/Automation] Launching game: " + reg.getId());
                    // Signal the actual game plugin to start via GameStartEvent
                    plugin.getServer().getPluginManager().callEvent(
                            new nl.kmc.core.event.GameStartEvent(reg, e.getTournament().getCurrentRound()));
                });
                // Engine waits for GameResultEvent — no timer here
            }
        };
    }

    private TournamentPhaseHandler gameEndCeremonyHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.GAME_END_CEREMONY; }
            public void enter(TournamentEngine e) {
                int durationSec = plugin.getConfig().getInt("automation.ceremony.game-end-duration", 10);
                broadcastPhase("§a§l▸ GAME COMPLETE ◂");
                schedule(durationSec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler roundEndCeremonyHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.ROUND_END_CEREMONY; }
            public void enter(TournamentEngine e) {
                int durationSec = plugin.getConfig().getInt("automation.ceremony.round-end-duration", 20);
                broadcastPhase("§6§l▸ ROUND " + e.getTournament().getCurrentRound() + " COMPLETE ◂");
                broadcastStandings(e);
                schedule(durationSec, () -> e.advance());
            }
        };
    }

    private TournamentPhaseHandler closingCeremonyHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.CLOSING_CEREMONY; }
            public void enter(TournamentEngine e) {
                int durationSec = plugin.getConfig().getInt("automation.ceremony.closing-duration", 30);
                broadcastPhase("§6§l★ TOURNAMENT COMPLETE ★");
                broadcastStandings(e);
                e.getTeams().getStandings().stream().findFirst().ifPresent(winner ->
                        plugin.getServer().broadcastMessage(
                                "§6§lCongratulations to §e" + winner.getColouredName() + "§6§l!"));
                schedule(durationSec, () -> {
                    e.getTournament().end();
                    e.transitionTo(TournamentPhase.ENDED);
                });
            }
        };
    }

    private TournamentPhaseHandler endedHandler() {
        return new TournamentPhaseHandler() {
            public TournamentPhase phase() { return TournamentPhase.ENDED; }
            public void enter(TournamentEngine e) {
                LOG.info("[KMC/Automation] Tournament has ended.");
                registry.resetPlayedList();
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcastPhase(String message) {
        plugin.getServer().broadcastMessage("");
        plugin.getServer().broadcastMessage("§8§m                              ");
        plugin.getServer().broadcastMessage("  " + message);
        plugin.getServer().broadcastMessage("§8§m                              ");
        plugin.getServer().broadcastMessage("");
    }

    private void broadcastStandings(TournamentEngine e) {
        plugin.getServer().broadcastMessage("§7Current Standings:");
        var standings = e.getTeams().getStandings();
        for (int i = 0; i < Math.min(standings.size(), 5); i++) {
            var team = standings.get(i);
            plugin.getServer().broadcastMessage(
                    "§7  " + (i + 1) + ". " + team.getColouredName()
                    + " §8— §e" + team.getPoints() + " pts");
        }
    }

    private void schedule(int delaySec, Runnable task) {
        plugin.getServer().getScheduler()
                .scheduleSyncDelayedTask(plugin, task, delaySec * 20L);
    }
}
