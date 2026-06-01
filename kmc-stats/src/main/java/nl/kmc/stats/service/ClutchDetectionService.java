package nl.kmc.stats.service;

import nl.kmc.core.event.ClutchMomentEvent;
import nl.kmc.core.event.KMCKillEvent;
import nl.kmc.core.event.PlayerEliminatedEvent;
import nl.kmc.stats.clutch.*;
import nl.kmc.stats.model.ClutchEvent;
import nl.kmc.stats.model.GameStats;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Runs all registered ClutchDetectors after each relevant game event
 * and fires ClutchMomentEvent on the server event bus when triggered.
 */
public final class ClutchDetectionService implements Listener {

    private static final Logger LOG = Logger.getLogger(ClutchDetectionService.class.getName());

    private final JavaPlugin         plugin;
    private final StatisticsService  stats;
    private final List<ClutchDetector> detectors;
    private final List<ClutchEvent>  history = new ArrayList<>();

    // Tracks context for each active game — updated by game plugins via event bus
    private int totalPlayers;
    private int remainingPlayers;
    private long gameStartMillis;
    private long gameTotalSeconds;
    private final Map<String, Integer> teamSizes = new HashMap<>();
    private final Map<String, Integer> teamAliveCounts = new HashMap<>();
    private String activeGameId;

    public ClutchDetectionService(JavaPlugin plugin, StatisticsService stats) {
        this.plugin = plugin;
        this.stats  = stats;
        this.detectors = List.of(
                new OutnumberedVictoryDetector(3),
                new LastSurvivorDetector(),
                new KillStreakDetector(4),
                new PerfectGameDetector()
        );
    }

    public void onGameStart(String gameId, int total, long durationSeconds,
                            Map<String, Integer> teamSizesIn) {
        this.activeGameId     = gameId;
        this.totalPlayers     = total;
        this.remainingPlayers = total;
        this.gameStartMillis  = System.currentTimeMillis();
        this.gameTotalSeconds = durationSeconds;
        this.teamSizes.clear();
        this.teamSizes.putAll(teamSizesIn);
        this.teamAliveCounts.clear();
        this.teamAliveCounts.putAll(teamSizesIn);
    }

    public void onPlayerEliminated(UUID uuid) {
        remainingPlayers = Math.max(0, remainingPlayers - 1);
        stats.getActiveStats(uuid).ifPresent(gs -> {
            if (gs.teamId != null) {
                teamAliveCounts.merge(gs.teamId, -1, Integer::sum);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(KMCKillEvent event) {
        evaluateAll(event.getKiller().getUuid());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(PlayerEliminatedEvent event) {
        onPlayerEliminated(event.getKmcPlayer().getUuid());
        evaluateAll(null); // re-evaluate last survivor scenarios
    }

    private void evaluateAll(UUID triggerPlayer) {
        if (activeGameId == null) return;
        Map<UUID, GameStats> allActive = new HashMap<>();
        stats.getActiveStatsList().forEach(gs -> allActive.put(gs.playerUuid, gs));

        long elapsed = (System.currentTimeMillis() - gameStartMillis) / 1000;
        boolean nearEnd = gameTotalSeconds > 0 && elapsed >= (gameTotalSeconds * 0.85);

        Set<UUID> toEvaluate = triggerPlayer != null
                ? Set.of(triggerPlayer)
                : allActive.keySet();

        for (UUID uuid : toEvaluate) {
            GameStats gs = allActive.get(uuid);
            if (gs == null) continue;

            int teamSize  = teamSizes.getOrDefault(gs.teamId, 1);
            int teamAlive = teamAliveCounts.getOrDefault(gs.teamId, 1);

            ClutchDetector.Context ctx = new ClutchDetector.Context(
                    totalPlayers, remainingPlayers, elapsed,
                    gameTotalSeconds, nearEnd, teamSize, teamAlive);

            for (ClutchDetector detector : detectors) {
                detector.evaluate(uuid, gs.playerName, activeGameId, gs, allActive, ctx)
                        .ifPresent(this::broadcast);
            }
        }
    }

    private void broadcast(ClutchMomentEvent event) {
        plugin.getServer().getPluginManager().callEvent(event);

        ClutchEvent ce = new ClutchEvent();
        ce.playerUuid = event.getPlayer().getUniqueId();
        ce.playerName = event.getPlayer().getName();
        ce.gameId     = event.getGameId();
        ce.type       = event.getType();
        ce.description = event.getDescription();
        history.add(ce);

        LOG.info("[KMC/Clutch] " + event.getType() + " — " + event.getPlayer().getName()
                 + ": " + event.getDescription());
    }

    public List<ClutchEvent> getHistory() { return List.copyOf(history); }

    public void reset() {
        history.clear();
        activeGameId = null;
        remainingPlayers = 0;
        teamSizes.clear();
        teamAliveCounts.clear();
    }
}
