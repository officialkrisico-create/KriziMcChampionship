package nl.kmc.game.api;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, shared "sounds:" config reader used by {@link BaseGameManager} to
 * give every KMC game a configurable start/end stinger without each one
 * re-implementing sound parsing (mirrors QuakeCraft's older per-plugin
 * {@code Sfx} utility, generalised to any {@link JavaPlugin}).
 *
 * <h3>Config format (in the game's own {@code config.yml})</h3>
 * <pre>
 * sounds:
 *   game-start: "ENTITY_ENDER_DRAGON_AMBIENT:1.0:1.2"   # vanilla enum
 *   game-end:   "kmc:fanfare.victory:1.0"                 # resource-pack key
 * </pre>
 * Each value is {@code NAME[:volume[:pitch]]}. If the key is missing or blank,
 * the caller's fallback {@link Sound} is used instead — a game is never silent.
 */
public final class GameSfx {

    private GameSfx() {}

    /** Plays a configured sound to every given player (skips null/offline entries). */
    public static void playToAll(JavaPlugin plugin, Iterable<Player> players, String configKey,
                                  Sound fallback, float fallbackVolume, float fallbackPitch) {
        for (Player p : players) playTo(plugin, p, configKey, fallback, fallbackVolume, fallbackPitch);
    }

    /** Plays a configured sound to one player. */
    public static void playTo(JavaPlugin plugin, Player p, String configKey,
                               Sound fallback, float fallbackVolume, float fallbackPitch) {
        if (p == null) return;
        String raw = plugin.getConfig().getString("sounds." + configKey);
        if (raw == null || raw.isBlank()) {
            if (fallback != null) p.playSound(p.getLocation(), fallback, fallbackVolume, fallbackPitch);
            return;
        }
        Parsed s = parse(raw, fallbackVolume, fallbackPitch);
        if (s.vanilla != null) p.playSound(p.getLocation(), s.vanilla, s.vol, s.pitch);
        else                   p.playSound(p.getLocation(), s.name, SoundCategory.PLAYERS, s.vol, s.pitch);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private record Parsed(Sound vanilla, String name, float vol, float pitch) {}

    private static Parsed parse(String raw, float defVol, float defPitch) {
        String[] parts = raw.split(":");
        String name;
        float vol = defVol, pitch = defPitch;

        Sound vanilla = tryVanilla(parts[0]);
        if (vanilla != null) {
            name = parts[0];
            if (parts.length >= 2) vol   = parseFloat(parts[1], defVol);
            if (parts.length >= 3) pitch = parseFloat(parts[2], defPitch);
        } else if (parts.length >= 2 && parts[0].matches("[a-z0-9_\\-.]+")) {
            // "namespace:path" resource-pack key
            name = parts[0] + ":" + parts[1];
            if (parts.length >= 3) vol   = parseFloat(parts[2], defVol);
            if (parts.length >= 4) pitch = parseFloat(parts[3], defPitch);
        } else {
            name = parts[0];
            if (parts.length >= 2) vol   = parseFloat(parts[1], defVol);
            if (parts.length >= 3) pitch = parseFloat(parts[2], defPitch);
        }
        return new Parsed(vanilla, name, vol, pitch);
    }

    private static Sound tryVanilla(String s) { return lookupVanillaSound(s); }

    /**
     * Looks up a {@link Sound} by name. {@code Sound} is no longer a plain
     * enum in modern Paper, so {@code Sound.valueOf(String)} is deprecated
     * (and throws {@link IncompatibleClassChangeError} at runtime on some
     * builds) — the supported replacement is {@code Registry.SOUNDS.get(key)}.
     *
     * <p>Accepts, in order:
     * <ul>
     *   <li>A fully-qualified namespaced key ({@code "minecraft:entity.ender_dragon.growl"})</li>
     *   <li>A bare dotted key ({@code "entity.ender_dragon.growl"})</li>
     *   <li>A legacy SCREAMING_SNAKE_CASE enum name ({@code "ENTITY_ENDER_DRAGON_GROWL"}),
     *       so existing {@code config.yml} values keep working unchanged</li>
     * </ul>
     *
     * @return the Sound, or {@code null} if nothing matched
     */
    public static Sound lookupVanillaSound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        List<NamespacedKey> attempts = new ArrayList<>();

        try {
            if (trimmed.contains(":")) {
                NamespacedKey k = NamespacedKey.fromString(trimmed.toLowerCase());
                if (k != null) attempts.add(k);
            }
            if (trimmed.contains(".") && !trimmed.contains(":")) {
                attempts.add(NamespacedKey.minecraft(trimmed.toLowerCase()));
            }
            if (trimmed.contains("_")) {
                // Legacy enum names flatten "category.thing.action" AND underscores inside words
                // (ITEM_ARMOR_EQUIP_ELYTRA = item.armor.equip_elytra), so there's no fixed rule for
                // which '_' were dots. Registry keys are unique enough that trying every placement
                // is safe: with at most ~6 underscores that's a few dozen cheap lookups.
                String lower = trimmed.toLowerCase();
                List<Integer> underscores = new ArrayList<>();
                for (int i = 0; i < lower.length(); i++) if (lower.charAt(i) == '_') underscores.add(i);
                int n = Math.min(underscores.size(), 10);
                for (int mask = (1 << n) - 1; mask >= 1; mask--) {   // most dots first: the common shapes
                    StringBuilder sb = new StringBuilder(lower);
                    for (int bit = 0; bit < n; bit++)
                        if ((mask & (1 << bit)) != 0) sb.setCharAt(underscores.get(bit), '.');
                    attempts.add(NamespacedKey.minecraft(sb.toString()));
                }
            }
            for (NamespacedKey key : attempts) {
                try {
                    Sound s = Registry.SOUNDS.get(key);
                    if (s != null) return s;
                } catch (Exception ignored) { /* try next */ }
            }
        } catch (Exception ignored) { /* fall through */ }
        return null;
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return def; }
    }
}
