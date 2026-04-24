package nl.kmc.kmccore.util;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Helper for broadcasting titles, subtitles, and sounds to all players.
 */
public final class AnnouncementUtil {

    private AnnouncementUtil() {}

    /**
     * Broadcasts a title/subtitle and plays a sound to every online player.
     *
     * <p>Config path expected:
     * <pre>
     *   announcements.{key}:
     *     title: "&6&lGame Start!"
     *     subtitle: "&e{game_name}"
     *     sound: ENTITY_ENDER_DRAGON_GROWL
     * </pre>
     *
     * @param plugin       plugin instance
     * @param configPath   key under {@code announcements} in config.yml
     * @param placeholders optional pairs of {placeholder} → value (may be null)
     */
    public static void broadcastTitle(KMCCore plugin, String configPath,
                                      String[] placeholders, String... values) {
        String rawTitle    = plugin.getConfig().getString(configPath + ".title",    "");
        String rawSubtitle = plugin.getConfig().getString(configPath + ".subtitle", "");
        String soundName   = plugin.getConfig().getString(configPath + ".sound",    "");

        if (placeholders != null) {
            for (int i = 0; i < placeholders.length && i < values.length; i++) {
                rawTitle    = rawTitle.replace(placeholders[i], values[i]);
                rawSubtitle = rawSubtitle.replace(placeholders[i], values[i]);
            }
        }

        String title    = MessageUtil.color(rawTitle);
        String subtitle = MessageUtil.color(rawSubtitle);

        Sound sound = null;
        if (!soundName.isBlank()) {
            try {
                sound = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid sound in config: " + soundName);
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, subtitle, 10, 70, 20);
            if (sound != null) p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        }
    }

    /**
     * Convenience overload when there are no placeholders to fill.
     */
    public static void broadcastTitle(KMCCore plugin, String configPath, Object ignored) {
        broadcastTitle(plugin, configPath, null);
    }

    /**
     * Broadcasts a coloured chat message to all players.
     *
     * @param plugin       plugin instance
     * @param messageKey   key in messages.yml
     * @param placeholder  single placeholder string (or null)
     * @param value        replacement value
     */
    public static void broadcastMessage(KMCCore plugin, String messageKey,
                                        String placeholder, String value) {
        String msg = MessageUtil.get(messageKey);
        if (placeholder != null) msg = msg.replace(placeholder, value);
        Bukkit.broadcastMessage(msg);
    }
}
