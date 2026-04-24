package nl.kmc.kmccore.util;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Utility class for loading and formatting plugin messages.
 *
 * <p>Messages are stored in {@code messages.yml} and cached here.
 * All message keys support {@code &}-style colour codes.
 */
public final class MessageUtil {

    private static FileConfiguration messages;
    private static String prefix = "&6[&eKMC&6] &r";

    private MessageUtil() {}

    /** Must be called once during plugin startup. */
    public static void init(KMCCore plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        prefix   = color(messages.getString("prefix", "&6[&eKMC&6] &r"));
    }

    /**
     * Returns the colour-translated message for the given key,
     * with the plugin prefix prepended.
     *
     * @param key dot-separated path in messages.yml (e.g. "team.not-found")
     * @return formatted message, or a fallback if key is missing
     */
    public static String get(String key) {
        String raw = messages.getString(key, "&c[Missing message: " + key + "]");
        return prefix + color(raw);
    }

    /**
     * Returns the message without the plugin prefix.
     * Useful for titles, subtitles, or lines in a list.
     */
    public static String getRaw(String key) {
        String raw = messages.getString(key, "&c[Missing: " + key + "]");
        return color(raw);
    }

    /**
     * Translates {@code &}-prefixed colour codes to Bukkit colour codes.
     */
    public static String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** @return the configured message prefix (already coloured). */
    public static String getPrefix() {
        return prefix;
    }
}
