package nl.kmc.kmccore.preferences;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Per-player, per-game preferences store.
 *
 * <p>Each game can save and load arbitrary key-value preferences for
 * each player. Persisted to {@code preferences.yml} in KMCCore's data
 * folder.
 *
 * <p>Examples of what games might store:
 * <ul>
 *   <li><b>SkyWars</b> — preferred kit selection</li>
 *   <li><b>Bingo</b> — favorite tasks list, alternate goals</li>
 *   <li><b>QuakeCraft</b> — gun skin preference</li>
 *   <li><b>Parkour</b> — auto-checkpoint resume</li>
 * </ul>
 *
 * <p>Usage from a game plugin:
 * <pre>
 *   var prefs = kmcCore.getPlayerPreferences();
 *   prefs.set(playerUuid, "skywars", "kit", "archer");
 *   String kit = prefs.getString(playerUuid, "skywars", "kit", "default");
 * </pre>
 *
 * <p>Saves are buffered — written to disk every 60s, on plugin
 * disable, and via /kmcprefs save.
 */
public class PlayerPreferencesManager {

    private final KMCCore plugin;
    private final File file;
    private FileConfiguration data;
    private boolean dirty;

    public PlayerPreferencesManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "preferences.yml");
        load();
        // Auto-save every 60 seconds
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                this::flushIfDirty, 1200L, 1200L);
    }

    public void load() {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create preferences.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
        dirty = false;
    }

    public void save() {
        try {
            data.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save preferences.yml: " + e.getMessage());
        }
    }

    private void flushIfDirty() {
        if (dirty) save();
    }

    // ----------------------------------------------------------------
    // Generic accessors
    // ----------------------------------------------------------------

    public void set(UUID uuid, String gameId, String key, Object value) {
        data.set(path(uuid, gameId, key), value);
        dirty = true;
    }

    public Object get(UUID uuid, String gameId, String key) {
        return data.get(path(uuid, gameId, key));
    }

    public String getString(UUID uuid, String gameId, String key, String def) {
        return data.getString(path(uuid, gameId, key), def);
    }

    public int getInt(UUID uuid, String gameId, String key, int def) {
        return data.getInt(path(uuid, gameId, key), def);
    }

    public boolean getBoolean(UUID uuid, String gameId, String key, boolean def) {
        return data.getBoolean(path(uuid, gameId, key), def);
    }

    public List<String> getStringList(UUID uuid, String gameId, String key) {
        return data.getStringList(path(uuid, gameId, key));
    }

    public boolean has(UUID uuid, String gameId, String key) {
        return data.contains(path(uuid, gameId, key));
    }

    public void remove(UUID uuid, String gameId, String key) {
        data.set(path(uuid, gameId, key), null);
        dirty = true;
    }

    /** Get all preferences for a player + game as a map. */
    public Map<String, Object> getAll(UUID uuid, String gameId) {
        ConfigurationSection sec = data.getConfigurationSection(uuid + "." + gameId);
        if (sec == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            result.put(key, sec.get(key));
        }
        return result;
    }

    /** Clear all preferences for a player (e.g. on /kmcprefs reset). */
    public void clearPlayer(UUID uuid) {
        data.set(uuid.toString(), null);
        dirty = true;
    }

    /** Clear preferences for a specific game across ALL players. */
    public void clearGame(String gameId) {
        for (String key : data.getKeys(false)) {
            data.set(key + "." + gameId, null);
        }
        dirty = true;
    }

    private String path(UUID uuid, String gameId, String key) {
        return uuid + "." + gameId + "." + key;
    }

    public void shutdown() {
        if (dirty) save();
    }
}
