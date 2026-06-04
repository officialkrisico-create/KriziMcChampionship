package nl.kmc.quake.util;

import nl.kmc.quake.QuakeCraftPlugin;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Configurable sound system.
 *
 * <p>Every weapon / gadget event (fire, impact, kill, equip, ready) is played
 * through here so server operators can re-theme the whole game from
 * {@code config.yml} — including resource-pack sounds — without touching code.
 *
 * <h3>Config format</h3>
 * <pre>
 * sounds:
 *   railgun.fire: "ENTITY_FIREWORK_ROCKET_BLAST:1.0:0.5"   # vanilla enum
 *   airstrike.incoming: "kmc:airstrike.whistle:1.0:1.0"     # resource-pack key
 *   kill.confirm: "BLOCK_NOTE_BLOCK_PLING:1.0:2.0"
 * </pre>
 *
 * <p>Each value is {@code NAME[:volume[:pitch]]}. If {@code NAME} matches a
 * vanilla {@link Sound} enum it is played as such; otherwise it is treated as a
 * custom (resource-pack) sound key. If a key is missing from config the
 * supplied fallback {@link Sound} is used, so nothing ever goes silent.
 */
public final class Sfx {

    private Sfx() {}

    /** Plays a configured sound at a location for everyone nearby. */
    public static void play(QuakeCraftPlugin plugin, Location loc, String key,
                            Sound fallback, float fVol, float fPitch) {
        if (loc == null || loc.getWorld() == null) return;
        String raw = plugin.getConfig().getString("sounds." + key);
        if (raw == null || raw.isBlank()) {
            if (fallback != null) loc.getWorld().playSound(loc, fallback, fVol, fPitch);
            return;
        }
        Parsed s = parse(raw, fVol, fPitch);
        if (s.vanilla != null) loc.getWorld().playSound(loc, s.vanilla, s.vol, s.pitch);
        else                   loc.getWorld().playSound(loc, s.name, SoundCategory.PLAYERS, s.vol, s.pitch);
    }

    /** Plays a configured sound to a single player (e.g. a kill-confirm ping). */
    public static void playTo(QuakeCraftPlugin plugin, Player p, String key,
                              Sound fallback, float fVol, float fPitch) {
        if (p == null) return;
        String raw = plugin.getConfig().getString("sounds." + key);
        if (raw == null || raw.isBlank()) {
            if (fallback != null) p.playSound(p.getLocation(), fallback, fVol, fPitch);
            return;
        }
        Parsed s = parse(raw, fVol, fPitch);
        if (s.vanilla != null) p.playSound(p.getLocation(), s.vanilla, s.vol, s.pitch);
        else                   p.playSound(p.getLocation(), s.name, SoundCategory.PLAYERS, s.vol, s.pitch);
    }

    /** Plays a global sound for every online player (e.g. legendary spawn / clutch stinger). */
    public static void playGlobal(QuakeCraftPlugin plugin, String key, Sound fallback, float fVol, float fPitch) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            playTo(plugin, p, key, fallback, fVol, fPitch);
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private record Parsed(Sound vanilla, String name, float vol, float pitch) {}

    private static Parsed parse(String raw, float defVol, float defPitch) {
        String[] parts = raw.split(":");
        // A resource-pack key looks like "namespace:path" → parts[0]=namespace, parts[1]=path,
        // optional vol/pitch follow. A vanilla key is just "ENUM[:vol[:pitch]]".
        String name;
        float vol = defVol, pitch = defPitch;

        Sound vanilla = tryVanilla(parts[0]);
        if (vanilla != null) {
            name = parts[0];
            if (parts.length >= 2) vol   = parseFloat(parts[1], defVol);
            if (parts.length >= 3) pitch = parseFloat(parts[2], defPitch);
        } else if (parts.length >= 2 && tryVanilla(parts[0] + ":" + parts[1]) == null
                && parts[0].matches("[a-z0-9_\\-.]+")) {
            // Treat as resource-pack key "namespace:path"
            name = parts[0] + ":" + parts[1];
            if (parts.length >= 3) vol   = parseFloat(parts[2], defVol);
            if (parts.length >= 4) pitch = parseFloat(parts[3], defPitch);
        } else {
            // Single token that isn't a vanilla enum → treat as a custom sound name
            name = parts[0];
            if (parts.length >= 2) vol   = parseFloat(parts[1], defVol);
            if (parts.length >= 3) pitch = parseFloat(parts[2], defPitch);
        }
        return new Parsed(vanilla, name, vol, pitch);
    }

    private static Sound tryVanilla(String s) {
        try { return Sound.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return def; }
    }
}
