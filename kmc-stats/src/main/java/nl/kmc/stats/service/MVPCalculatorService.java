package nl.kmc.stats.service;

import nl.kmc.stats.model.GameStats;
import nl.kmc.stats.model.MVPResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Calculates MVP using a configurable weighted formula from points.yml.
 * Formula: score = (kills * killWeight) + (pointsEarned * pointsWeight) + (won ? wonBonus : 0)
 */
public class MVPCalculatorService {

    private static final Logger LOG = Logger.getLogger(MVPCalculatorService.class.getName());

    private final JavaPlugin plugin;

    // Default weights — overridden by points.yml mvp section
    private final Map<String, Map<String, Double>> gameWeights = new HashMap<>();
    private Map<String, Double> defaultWeights = Map.of(
            "kills", 2.0, "points-earned", 1.0, "won", 50.0,
            "survival-time", 0.0, "objectives", 5.0);

    public MVPCalculatorService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "points.yml");
        if (!file.exists()) return;
        var cfg = YamlConfiguration.loadConfiguration(file);

        // Load default weights
        var defSection = cfg.getConfigurationSection("mvp.per-game.default");
        if (defSection != null) {
            Map<String, Double> def = new HashMap<>();
            defSection.getKeys(false).forEach(k -> def.put(k, defSection.getDouble(k)));
            defaultWeights = def;
        }

        // Load per-game weights
        var perGame = cfg.getConfigurationSection("mvp.per-game");
        if (perGame != null) {
            for (String gameId : perGame.getKeys(false)) {
                if ("default".equals(gameId)) continue;
                var section = perGame.getConfigurationSection(gameId);
                if (section == null) continue;
                Map<String, Double> weights = new HashMap<>();
                section.getKeys(false).forEach(k -> weights.put(k, section.getDouble(k)));
                gameWeights.put(gameId, weights);
            }
        }
        LOG.info("[KMC/MVP] Config loaded for " + (gameWeights.size() + 1) + " game profiles.");
    }

    /**
     * Computes game MVP from a list of per-player GameStats.
     * Returns empty if no stats available.
     */
    public Optional<MVPResult> calculateGameMVP(String gameId, List<GameStats> stats) {
        if (stats == null || stats.isEmpty()) return Optional.empty();

        Map<String, Double> weights = gameWeights.getOrDefault(gameId, defaultWeights);

        GameStats best = null;
        double    bestScore = -1;

        for (GameStats gs : stats) {
            double score = 0;
            score += gs.kills              * weights.getOrDefault("kills", 2.0);
            score += gs.pointsEarned       * weights.getOrDefault("points-earned", 1.0);
            score += gs.objectivesCompleted * weights.getOrDefault("objectives", 5.0);
            score += gs.survivalSeconds / 60.0 * weights.getOrDefault("survival-time", 0.0);
            if (gs.won) score += weights.getOrDefault("won", 50.0);

            if (score > bestScore) { bestScore = score; best = gs; }
        }

        if (best == null) return Optional.empty();

        MVPResult result = new MVPResult(best.playerUuid, best.playerName, gameId,
                MVPResult.Scope.GAME, bestScore);
        result.kills       = best.kills;
        result.pointsEarned = best.pointsEarned;
        result.won         = best.won;
        return Optional.of(result);
    }

    /**
     * Computes the tournament-wide MVP from all game stats.
     */
    public Optional<MVPResult> calculateTournamentMVP(List<GameStats> allStats) {
        if (allStats == null || allStats.isEmpty()) return Optional.empty();

        // Aggregate per player
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, String> names  = new HashMap<>();

        for (GameStats gs : allStats) {
            double s = gs.kills * 1.0 + gs.pointsEarned * 0.5;
            scores.merge(gs.playerUuid, s, Double::sum);
            names.put(gs.playerUuid, gs.playerName);
        }

        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> new MVPResult(e.getKey(), names.get(e.getKey()),
                        null, MVPResult.Scope.TOURNAMENT, e.getValue()));
    }
}
