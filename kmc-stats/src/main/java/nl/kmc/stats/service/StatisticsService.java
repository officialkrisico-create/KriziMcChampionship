package nl.kmc.stats.service;

import nl.kmc.core.event.GameEndEvent;
import nl.kmc.core.event.KMCKillEvent;
import nl.kmc.core.service.PlayerService;
import nl.kmc.stats.model.GameStats;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central stats aggregator. Tracks per-game stats in memory during a game
 * and persists a snapshot to history when the game ends.
 */
public final class StatisticsService implements Listener {

    private static final Logger LOG = Logger.getLogger(StatisticsService.class.getName());

    private final JavaPlugin    plugin;
    private final PlayerService players;

    // Active game stats accumulator: playerUuid → GameStats
    private final Map<UUID, GameStats> activeStats = new ConcurrentHashMap<>();
    // All completed game stat records this tournament
    private final List<GameStats> history = new ArrayList<>();

    public StatisticsService(JavaPlugin plugin, PlayerService players) {
        this.plugin  = plugin;
        this.players = players;
    }

    // ── Game lifecycle ────────────────────────────────────────────────────────

    public void onGameStart(String gameId, int round, Collection<UUID> participants) {
        activeStats.clear();
        participants.forEach(uuid -> {
            players.get(uuid).ifPresent(p -> {
                GameStats gs = new GameStats();
                gs.playerUuid = uuid;
                gs.playerName = p.getName();
                gs.teamId     = p.getTeamId();
                gs.gameId     = gameId;
                gs.round      = round;
                activeStats.put(uuid, gs);
            });
        });
    }

    public void onGameEnd() {
        history.addAll(activeStats.values());
        activeStats.clear();
    }

    // ── Stat recording API (called by game plugins via game-api events) ───────

    public void recordKill(UUID killer) {
        GameStats gs = activeStats.get(killer);
        if (gs != null) gs.kills++;
    }

    public void recordDeath(UUID victim) {
        GameStats gs = activeStats.get(victim);
        if (gs != null) gs.deaths++;
    }

    public void recordAssist(UUID player) {
        GameStats gs = activeStats.get(player);
        if (gs != null) gs.assists++;
    }

    public void recordDamageDealt(UUID player, int amount) {
        GameStats gs = activeStats.get(player);
        if (gs != null) gs.damageDealt += amount;
    }

    public void recordObjective(UUID player) {
        GameStats gs = activeStats.get(player);
        if (gs != null) gs.objectivesCompleted++;
    }

    public void recordPointsEarned(UUID player, int amount) {
        GameStats gs = activeStats.get(player);
        if (gs != null) gs.pointsEarned += amount;
    }

    public void recordPlacement(UUID player, int placement) {
        GameStats gs = activeStats.get(player);
        if (gs != null) { gs.placement = placement; gs.won = (placement == 1); }
    }

    public void recordSurvivalSeconds(UUID player, long seconds) {
        GameStats gs = activeStats.get(player);
        if (gs != null) gs.survivalSeconds = seconds;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Optional<GameStats> getActiveStats(UUID uuid) {
        return Optional.ofNullable(activeStats.get(uuid));
    }

    public List<GameStats> getActiveStatsList() {
        return List.copyOf(activeStats.values());
    }

    public List<GameStats> getHistory() { return List.copyOf(history); }

    public List<GameStats> getHistoryForGame(String gameId) {
        return history.stream().filter(gs -> gameId.equals(gs.gameId)).toList();
    }

    // ── Event hooks ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(KMCKillEvent event) {
        recordKill(event.getKiller().getUuid());
        recordDeath(event.getVictim().getUuid());
    }
}
