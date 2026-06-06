package nl.kmc.kmccore.lang;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Lightweight i18n: per-player language with {@code &}-colour + {@code {0}}
 * placeholder support. Bundles live in {@code lang/<code>.yml}; the player's
 * choice is stored via the preferences manager. Any key not found in the
 * chosen language falls back to the default language, then to the raw key.
 */
public final class LanguageManager {

    private static final String DEFAULT = "nl";
    private static final String[] BUNDLED = {"nl", "en"};
    private static final String PREF_GAME = "core";
    private static final String PREF_KEY  = "language";

    private final KMCCore plugin;
    private final Map<String, FileConfiguration> bundles = new LinkedHashMap<>();

    public LanguageManager(KMCCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        bundles.clear();
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists()) dir.mkdirs();
        for (String code : BUNDLED) {
            plugin.saveResource("lang/" + code + ".yml", false); // copies if absent
            File f = new File(dir, code + ".yml");
            if (f.exists()) bundles.put(code, YamlConfiguration.loadConfiguration(f));
        }
    }

    /** Language codes that have a loaded bundle (e.g. "nl", "en"). */
    public Set<String> available() { return Collections.unmodifiableSet(bundles.keySet()); }

    /** Human-readable name of a language ("Nederlands", "English"). */
    public String displayName(String code) {
        FileConfiguration b = bundles.get(code);
        return b != null ? b.getString("language.name", code) : code;
    }

    public String getLanguage(UUID uuid) {
        String code = plugin.getPlayerPreferences().getString(uuid, PREF_GAME, PREF_KEY, DEFAULT);
        return bundles.containsKey(code) ? code : DEFAULT;
    }

    public void setLanguage(UUID uuid, String code) {
        if (!bundles.containsKey(code)) code = DEFAULT;
        plugin.getPlayerPreferences().set(uuid, PREF_GAME, PREF_KEY, code);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /** Translates {@code key} for a player's chosen language, with placeholders. */
    public String tr(Player player, String key, Object... args) {
        return tr(getLanguage(player.getUniqueId()), key, args);
    }

    /** Translates for any sender (players get their language; console gets default). */
    public String tr(CommandSender sender, String key, Object... args) {
        return sender instanceof Player p ? tr(p, key, args) : tr(DEFAULT, key, args);
    }

    /** Translates for a player by UUID (used by the shared API for games). */
    public String tr(UUID uuid, String key, Object... args) {
        return tr(uuid != null ? getLanguage(uuid) : DEFAULT, key, args);
    }

    public String tr(String code, String key, Object... args) {
        String raw = lookup(code, key);
        if (raw == null) raw = lookup(DEFAULT, key);
        if (raw == null) raw = key;
        for (int i = 0; i < args.length; i++) {
            raw = raw.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String lookup(String code, String key) {
        FileConfiguration b = bundles.get(code);
        return b != null ? b.getString(key, null) : null;
    }

    public void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(tr(sender, key, args));
    }
}
