package nl.kmc.kmccore.util;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Helper for broadcasting titles, subtitles, and sounds to all players.
 *
 * <p><b>BUG-FIX VERSION:</b>
 * <ul>
 *   <li>Removed the recursive {@code broadcastTitle(plugin, path, Object)}
 *       overload that caused {@link StackOverflowError} on first invocation.</li>
 *   <li>Replaced {@code Sound.valueOf(String)} with the Paper 1.21+
 *       {@link Registry#SOUNDS} lookup. {@code Sound} is no longer an
 *       enum in modern Paper, so {@code valueOf} throws
 *       {@link IncompatibleClassChangeError} at runtime.</li>
 * </ul>
 */
public final class AnnouncementUtil {

    private AnnouncementUtil() {}

    /**
     * Broadcasts a title/subtitle and plays a sound to every online player.
     *
     * <p>Config path expected:
     * <pre>
     *   announcements.{key}:
     *     title: "&amp;6&amp;lGame Start!"
     *     subtitle: "&amp;e{game_name}"
     *     sound: ENTITY_ENDER_DRAGON_GROWL
     * </pre>
     *
     * @param plugin       plugin instance
     * @param configPath   key under {@code announcements} in config.yml
     * @param placeholders optional pairs of {placeholder} → value (may be null)
     * @param values       replacement values matching {@code placeholders}
     */
    public static void broadcastTitle(KMCCore plugin, String configPath,
                                      String[] placeholders, String... values) {
        String rawTitle    = plugin.getConfig().getString(configPath + ".title",    "");
        String rawSubtitle = plugin.getConfig().getString(configPath + ".subtitle", "");
        String soundName   = plugin.getConfig().getString(configPath + ".sound",    "");

        if (placeholders != null && values != null) {
            for (int i = 0; i < placeholders.length && i < values.length; i++) {
                rawTitle    = rawTitle.replace(placeholders[i], values[i]);
                rawSubtitle = rawSubtitle.replace(placeholders[i], values[i]);
            }
        }

        String title    = MessageUtil.color(rawTitle);
        String subtitle = MessageUtil.color(rawSubtitle);

        Sound sound = lookupSound(plugin, soundName);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, subtitle, 10, 70, 20);
            if (sound != null) p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        }
    }

    /**
     * Broadcasts a title with no placeholder substitutions.
     *
     * <p>Use this when your title/subtitle text is fully static. Delegates
     * safely to the 4-arg version with empty placeholder arrays — no recursion.
     */
    public static void broadcastTitle(KMCCore plugin, String configPath) {
        broadcastTitle(plugin, configPath, new String[0], new String[0]);
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
        if (placeholder != null && value != null) {
            msg = msg.replace(placeholder, value);
        }
        Bukkit.broadcastMessage(msg);
    }

    // ----------------------------------------------------------------
    // Sound lookup — Paper 1.21+ compatible
    // ----------------------------------------------------------------

    /**
     * Looks up a {@link Sound} by name. In Paper 1.21+, {@code Sound} is
     * no longer an enum, so {@code Sound.valueOf(String)} throws
     * {@link IncompatibleClassChangeError} at runtime. The correct modern
     * API is {@code Registry.SOUNDS.get(key)}.
     *
     * <p>Accepts:
     * <ul>
     *   <li>Modern namespaced key: {@code "minecraft:entity.ender_dragon.growl"}</li>
     *   <li>Modern key without namespace: {@code "entity.ender_dragon.growl"}</li>
     *   <li>Legacy enum name: {@code "ENTITY_ENDER_DRAGON_GROWL"}</li>
     * </ul>
     *
     * @return the Sound, or {@code null} if not found / config blank
     */
    private static Sound lookupSound(KMCCore plugin, String soundName) {
        if (soundName == null || soundName.isBlank()) return null;
        String trimmed = soundName.trim();

        // Try several key formats in order of likelihood
        java.util.List<NamespacedKey> attempts = new java.util.ArrayList<>();

        try {
            // 1. As-is, fully qualified ("minecraft:foo.bar")
            if (trimmed.contains(":")) {
                NamespacedKey k = NamespacedKey.fromString(trimmed.toLowerCase());
                if (k != null) attempts.add(k);
            }

            // 2. Already in dot-notation, no namespace ("entity.ender_dragon.growl")
            if (trimmed.contains(".") && !trimmed.contains(":")) {
                attempts.add(NamespacedKey.minecraft(trimmed.toLowerCase()));
            }

            // 3. Legacy enum-style: ENTITY_ENDER_DRAGON_GROWL → entity.ender_dragon.growl
            //    Heuristic: replace LAST underscore with dot, then convert FIRST
            //    underscore to dot. So XXX_YYY_ZZZ_WWW → xxx.yyy_zzz.www doesn't
            //    quite work — instead we walk both ends:
            //      ENTITY_ENDER_DRAGON_GROWL
            //      → entity.ender_dragon.growl  (first '_' = dot, last '_' = dot)
            //      BLOCK_NOTE_BLOCK_HAT
            //      → block.note_block.hat
            //    For 2-segment names like UI_TOAST_CHALLENGE_COMPLETE that
            //    don't follow the rule, we just try multiple variations.
            if (trimmed.contains("_")) {
                String lower = trimmed.toLowerCase();

                // Variant A: first '_' → '.', last '_' → '.', rest stay '_'
                int firstUnderscore = lower.indexOf('_');
                int lastUnderscore  = lower.lastIndexOf('_');
                if (firstUnderscore != lastUnderscore && firstUnderscore >= 0) {
                    StringBuilder sb = new StringBuilder(lower);
                    sb.setCharAt(lastUnderscore, '.');
                    sb.setCharAt(firstUnderscore, '.');
                    attempts.add(NamespacedKey.minecraft(sb.toString()));
                }

                // Variant B: only first '_' → '.', rest stay '_'
                if (firstUnderscore >= 0) {
                    String v = lower.substring(0, firstUnderscore) + "."
                            + lower.substring(firstUnderscore + 1);
                    attempts.add(NamespacedKey.minecraft(v));
                }

                // Variant C: every '_' → '.'  (last-resort fallback)
                attempts.add(NamespacedKey.minecraft(lower.replace('_', '.')));
            }

            // Try them in order
            for (NamespacedKey key : attempts) {
                try {
                    Sound s = Registry.SOUNDS.get(key);
                    if (s != null) return s;
                } catch (Exception ignored) { /* try next */ }
            }
        } catch (Exception ignored) {
            // fall through
        }

        plugin.getLogger().warning("Invalid sound in config: " + soundName
                + " (tried " + attempts.size() + " key variants)");
        return null;
    }
}