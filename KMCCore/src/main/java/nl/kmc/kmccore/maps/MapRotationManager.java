package nl.kmc.kmccore.maps;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Map rotation system for games that support multiple arenas.
 *
 * <p>Each game can register multiple named maps. KMCCore picks one
 * per game in different ways:
 * <ul>
 *   <li><b>RANDOM</b> — picked uniformly at random</li>
 *   <li><b>WEIGHTED</b> — picked by weight (define popularity)</li>
 *   <li><b>VOTE</b> — players vote between rounds (limited options)</li>
 *   <li><b>SEQUENCE</b> — cycles through in order</li>
 *   <li><b>FIXED</b> — admin pinned a specific map</li>
 * </ul>
 *
 * <p>Storage in {@code maps.yml}:
 * <pre>
 *   maps:
 *     team_skywars:
 *       mode: RANDOM
 *       maps:
 *         nordic:
 *           weight: 1
 *           display-name: "Nordic Islands"
 *         desert:
 *           weight: 1
 *           display-name: "Desert Mesa"
 *     survival_games:
 *       mode: VOTE
 *       maps:
 *         forest: { display-name: "Mirkwood" }
 *         arctic: { display-name: "Frostfall" }
 * </pre>
 *
 * <p>Game plugins ask for the next map via:
 * <pre>
 *   String mapId = kmcCore.getMapRotation().pickNext("team_skywars");
 * </pre>
 *
 * <p>The game plugin is responsible for loading the map's arena
 * config (e.g. SkyWars stores islands per-map under
 * {@code skywars-maps/&lt;mapId&gt;.yml}).
 */
public class MapRotationManager {

    public enum SelectionMode { RANDOM, WEIGHTED, VOTE, SEQUENCE, FIXED }

    public record MapInfo(String id, String displayName, int weight) {}

    private final KMCCore plugin;
    private final File file;
    private final Map<String, GameMapConfig> games = new LinkedHashMap<>();
    private final Map<String, String> lastPicked = new HashMap<>();
    private final Map<String, Integer> sequenceCursor = new HashMap<>();

    public MapRotationManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "maps.yml");
        load();
    }

    private static class GameMapConfig {
        SelectionMode mode = SelectionMode.RANDOM;
        String fixedId;       // for FIXED mode
        Map<String, MapInfo> maps = new LinkedHashMap<>();
    }

    public void load() {
        games.clear();
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create maps.yml: " + e.getMessage());
            }
            return;
        }

        var data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = data.getConfigurationSection("maps");
        if (root == null) return;

        for (String gameId : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(gameId);
            if (s == null) continue;
            GameMapConfig cfg = new GameMapConfig();
            try {
                cfg.mode = SelectionMode.valueOf(s.getString("mode", "RANDOM").toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Bad map mode for " + gameId + ", defaulting to RANDOM");
            }
            cfg.fixedId = s.getString("fixed");

            ConfigurationSection mapsSec = s.getConfigurationSection("maps");
            if (mapsSec != null) {
                for (String mapId : mapsSec.getKeys(false)) {
                    ConfigurationSection ms = mapsSec.getConfigurationSection(mapId);
                    if (ms == null) continue;
                    String name = ms.getString("display-name", mapId);
                    int weight = ms.getInt("weight", 1);
                    cfg.maps.put(mapId, new MapInfo(mapId, name, weight));
                }
            }
            games.put(gameId, cfg);
        }

        plugin.getLogger().info("Loaded map configurations for " + games.size() + " game(s).");
    }

    public void save() {
        var data = new org.bukkit.configuration.file.YamlConfiguration();
        for (var entry : games.entrySet()) {
            String gameId = entry.getKey();
            GameMapConfig cfg = entry.getValue();
            data.set("maps." + gameId + ".mode", cfg.mode.name());
            if (cfg.fixedId != null) data.set("maps." + gameId + ".fixed", cfg.fixedId);
            for (var m : cfg.maps.values()) {
                data.set("maps." + gameId + ".maps." + m.id() + ".display-name", m.displayName());
                data.set("maps." + gameId + ".maps." + m.id() + ".weight", m.weight());
            }
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save maps.yml: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    public boolean isConfigured(String gameId) {
        GameMapConfig cfg = games.get(gameId);
        return cfg != null && !cfg.maps.isEmpty();
    }

    public Collection<MapInfo> getMaps(String gameId) {
        GameMapConfig cfg = games.get(gameId);
        return cfg != null ? cfg.maps.values() : Collections.emptyList();
    }

    public SelectionMode getMode(String gameId) {
        GameMapConfig cfg = games.get(gameId);
        return cfg != null ? cfg.mode : SelectionMode.RANDOM;
    }

    /** Picks the next map for the given game. Returns null if no maps configured. */
    public String pickNext(String gameId) {
        GameMapConfig cfg = games.get(gameId);
        if (cfg == null || cfg.maps.isEmpty()) return null;

        String pickedId;
        switch (cfg.mode) {
            case FIXED -> {
                if (cfg.fixedId != null && cfg.maps.containsKey(cfg.fixedId)) {
                    pickedId = cfg.fixedId;
                } else {
                    pickedId = cfg.maps.keySet().iterator().next();
                }
            }
            case SEQUENCE -> {
                List<String> ids = new ArrayList<>(cfg.maps.keySet());
                int idx = sequenceCursor.getOrDefault(gameId, 0);
                pickedId = ids.get(idx % ids.size());
                sequenceCursor.put(gameId, idx + 1);
            }
            case WEIGHTED -> {
                int totalWeight = 0;
                for (MapInfo m : cfg.maps.values()) totalWeight += Math.max(1, m.weight());
                int roll = new Random().nextInt(totalWeight);
                pickedId = null;
                for (MapInfo m : cfg.maps.values()) {
                    roll -= Math.max(1, m.weight());
                    if (roll < 0) { pickedId = m.id(); break; }
                }
                if (pickedId == null) pickedId = cfg.maps.keySet().iterator().next();
            }
            case VOTE -> {
                // VOTE mode = pickNext returns null if it's the first call;
                // the AutomationManager should call openVote(gameId) instead.
                // Falls back to RANDOM if vote isn't initiated.
                pickedId = pickRandom(cfg);
            }
            case RANDOM -> pickedId = pickRandom(cfg);
            default -> pickedId = pickRandom(cfg);
        }
        lastPicked.put(gameId, pickedId);
        return pickedId;
    }

    /** Get the most recently picked map (for the active or just-finished game). */
    public String getLastPicked(String gameId) {
        return lastPicked.get(gameId);
    }

    private String pickRandom(GameMapConfig cfg) {
        List<String> ids = new ArrayList<>(cfg.maps.keySet());
        return ids.get(new Random().nextInt(ids.size()));
    }

    /** Programmatically register a map (mainly for testing or admin commands). */
    public void registerMap(String gameId, String mapId, String displayName, int weight) {
        GameMapConfig cfg = games.computeIfAbsent(gameId, k -> new GameMapConfig());
        cfg.maps.put(mapId, new MapInfo(mapId, displayName, weight));
        save();
    }

    public void removeMap(String gameId, String mapId) {
        GameMapConfig cfg = games.get(gameId);
        if (cfg != null) {
            cfg.maps.remove(mapId);
            save();
        }
    }

    public void setMode(String gameId, SelectionMode mode) {
        GameMapConfig cfg = games.computeIfAbsent(gameId, k -> new GameMapConfig());
        cfg.mode = mode;
        save();
    }

    public void setFixed(String gameId, String mapId) {
        GameMapConfig cfg = games.computeIfAbsent(gameId, k -> new GameMapConfig());
        cfg.mode = SelectionMode.FIXED;
        cfg.fixedId = mapId;
        save();
    }
}
