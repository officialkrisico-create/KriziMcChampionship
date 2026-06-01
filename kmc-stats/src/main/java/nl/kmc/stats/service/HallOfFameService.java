package nl.kmc.stats.service;

import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.event.TournamentEndEvent;
import nl.kmc.core.service.PlayerService;
import nl.kmc.stats.model.ClutchEvent;
import nl.kmc.stats.model.HallOfFameRecord;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Manages the 8-category Hall of Fame.
 * Automatically evaluates and updates records at tournament end.
 */
public final class HallOfFameService implements Listener {

    private static final Logger LOG = Logger.getLogger(HallOfFameService.class.getName());

    private final JavaPlugin           plugin;
    private final PlayerService        players;
    private final StatisticsService    stats;
    private final ClutchDetectionService clutch;

    // Current HOF records: one per category
    private final Map<HallOfFameRecord.Category, HallOfFameRecord> records = new EnumMap<>(HallOfFameRecord.Category.class);

    // Per-player MVP win count this tournament
    private final Map<UUID, Integer> mvpCounts = new HashMap<>();

    public HallOfFameService(JavaPlugin plugin, PlayerService players,
                             StatisticsService stats, ClutchDetectionService clutch) {
        this.plugin  = plugin;
        this.players = players;
        this.stats   = stats;
        this.clutch  = clutch;
    }

    public void recordMVP(UUID uuid) {
        mvpCounts.merge(uuid, 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTournamentEnd(TournamentEndEvent event) {
        evaluate(event.getEventNumber());
    }

    public void evaluate(int eventNumber) {
        List<KMCPlayer> all = players.getAllPlayers();
        if (all.isEmpty()) return;

        updateCategory(HallOfFameRecord.Category.MOST_WINS, all, KMCPlayer::getWins, eventNumber);
        updateCategory(HallOfFameRecord.Category.MOST_KILLS, all, KMCPlayer::getKills, eventNumber);

        // MVP count
        mvpCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> players.get(e.getKey()).ifPresent(p ->
                        records.put(HallOfFameRecord.Category.MOST_MVPS,
                                new HallOfFameRecord(HallOfFameRecord.Category.MOST_MVPS,
                                        p.getUuid(), p.getName(), e.getValue(), eventNumber))));

        // Clutch count
        Map<UUID, Long> clutchCounts = new HashMap<>();
        clutch.getHistory().forEach(ce -> clutchCounts.merge(ce.playerUuid, 1L, Long::sum));
        clutchCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> players.get(e.getKey()).ifPresent(p ->
                        records.put(HallOfFameRecord.Category.MOST_CLUTCHES,
                                new HallOfFameRecord(HallOfFameRecord.Category.MOST_CLUTCHES,
                                        p.getUuid(), p.getName(), e.getValue(), eventNumber))));

        // Win streak
        all.stream().max(Comparator.comparingInt(KMCPlayer::getBestWinStreak))
                .ifPresent(p -> records.put(HallOfFameRecord.Category.LONGEST_STREAK,
                        new HallOfFameRecord(HallOfFameRecord.Category.LONGEST_STREAK,
                                p.getUuid(), p.getName(), p.getBestWinStreak(), eventNumber)));

        LOG.info("[KMC/HallOfFame] Updated after event #" + eventNumber + ".");
    }

    private void updateCategory(HallOfFameRecord.Category cat,
                                List<KMCPlayer> players,
                                java.util.function.ToIntFunction<KMCPlayer> metric,
                                int eventNumber) {
        players.stream().max(Comparator.comparingInt(metric))
                .filter(p -> metric.applyAsInt(p) > 0)
                .ifPresent(p -> {
                    double value = metric.applyAsInt(p);
                    HallOfFameRecord existing = records.get(cat);
                    if (existing == null || value >= existing.value) {
                        records.put(cat, new HallOfFameRecord(cat, p.getUuid(),
                                p.getName(), value, eventNumber));
                    }
                });
    }

    public Optional<HallOfFameRecord> getRecord(HallOfFameRecord.Category category) {
        return Optional.ofNullable(records.get(category));
    }

    public Map<HallOfFameRecord.Category, HallOfFameRecord> getAllRecords() {
        return Collections.unmodifiableMap(records);
    }

    public void reset() {
        mvpCounts.clear();
    }
}
