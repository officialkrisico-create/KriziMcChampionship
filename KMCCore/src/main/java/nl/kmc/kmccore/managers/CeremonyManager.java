package nl.kmc.kmccore.managers;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages {@code ceremonies.yml} — per-phase messages, titles, subtitles,
 * and durations for the tournament ceremony phases.
 *
 * <p>Reload at any time with {@link #reload()} or via {@code /kmcceremonies reload}.
 * Changes take effect on the next time that phase is entered.
 */
public final class CeremonyManager {

    private final KMCCore plugin;
    private final File    file;
    private FileConfiguration config;
    /** The jar's own ceremonies.yml — used for phases/keys an older on-disk file (from a previous version) lacks. */
    private FileConfiguration jarDefaults;

    /** All known phase keys (matches ceremonies.yml top-level keys). */
    public static final List<String> PHASES = List.of(
            "opening", "how-it-works", "tournament-overview", "team-showcase",
            "game-lineup", "voting", "game-intro",
            "game-end", "round-end", "closing");

    public CeremonyManager(KMCCore plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "ceremonies.yml");
        if (!file.exists()) plugin.saveResource("ceremonies.yml", false);
        reload();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Reloads ceremonies.yml from disk. */
    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        jarDefaults = null;
        try (var in = plugin.getResource("ceremonies.yml")) {
            if (in != null) jarDefaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[CeremonyManager] Could not read bundled ceremonies.yml defaults.");
        }
        plugin.getLogger().info("[CeremonyManager] Loaded ceremonies.yml.");
    }

    /** The on-disk config if it defines {@code path}, otherwise the jar's default (admin edits always win). */
    private FileConfiguration src(String path) {
        if (config.isSet(path) || jarDefaults == null || !jarDefaults.isSet(path)) return config;
        return jarDefaults;
    }

    /** Duration in seconds for a phase (falls back to provided default if not set). */
    public int getDuration(String phase, int defaultSec) {
        String p = phase + ".duration-seconds";
        return src(p).getInt(p, defaultSec);
    }

    /** Minimum ticks each chat block stays alone on screen before the next one appears. */
    public long getLineDelayTicks() {
        return Math.max(10, src("pacing.line-delay-ticks").getLong("pacing.line-delay-ticks", 40L));
    }

    /** Extra ticks per visible character of a block — longer lines get proportionally more reading time. */
    public double getTicksPerChar() {
        return Math.max(0, src("pacing.ticks-per-char").getDouble("pacing.ticks-per-char", 1.3));
    }

    /** Minimum ticks to let players read after a stage's last line before moving on. */
    public long getReadBufferTicks() {
        return Math.max(0, src("pacing.read-buffer-ticks").getLong("pacing.read-buffer-ticks", 50L));
    }

    /** Seconds each game is spotlighted in the game-lineup stage. */
    public int getSecondsPerGame() {
        return Math.max(2, src("game-lineup.seconds-per-game").getInt("game-lineup.seconds-per-game", 4));
    }

    /**
     * Returns the messages list for a phase, with placeholders replaced.
     * Returns an empty list if none are configured.
     */
    public List<String> getMessages(String phase, Map<String, String> placeholders) {
        String p = phase + ".messages";
        List<String> raw = src(p).getStringList(p);
        return raw.stream()
                .map(line -> applyColor(applyPlaceholders(line, placeholders)))
                .toList();
    }

    /** Title text for the phase, or empty string if not configured. */
    public String getTitle(String phase, Map<String, String> placeholders) {
        String p = phase + ".title";
        String raw = src(p).getString(p, "");
        return applyColor(applyPlaceholders(raw, placeholders));
    }

    /** Subtitle text for the phase, or empty string if not configured. */
    public String getSubtitle(String phase, Map<String, String> placeholders) {
        String p = phase + ".subtitle";
        String raw = src(p).getString(p, "");
        return applyColor(applyPlaceholders(raw, placeholders));
    }

    // ── In-game editing ───────────────────────────────────────────────────────

    /** Sets the duration for a phase and saves to disk. */
    public void setDuration(String phase, int seconds) {
        config.set(phase + ".duration-seconds", seconds);
        save();
    }

    /** Sets the title for a phase and saves. */
    public void setTitle(String phase, String title) {
        config.set(phase + ".title", title);
        save();
    }

    /** Sets the subtitle for a phase and saves. */
    public void setSubtitle(String phase, String subtitle) {
        config.set(phase + ".subtitle", subtitle);
        save();
    }

    /** Adds a message line to a phase and saves. */
    public void addMessage(String phase, String message) {
        List<String> lines = src(phase + ".messages").getStringList(phase + ".messages");
        lines.add(message);
        config.set(phase + ".messages", lines);
        save();
    }

    /** Clears all messages for a phase and saves. */
    public void clearMessages(String phase) {
        config.set(phase + ".messages", List.of());
        save();
    }

    /** Sets a specific message line (0-indexed) for a phase and saves. */
    public void setMessage(String phase, int index, String message) {
        List<String> lines = src(phase + ".messages").getStringList(phase + ".messages");
        if (index < 0 || index >= lines.size()) return;
        lines.set(index, message);
        config.set(phase + ".messages", lines);
        save();
    }

    /** Removes a specific message line (0-indexed) from a phase and saves. */
    public void removeMessage(String phase, int index) {
        List<String> lines = src(phase + ".messages").getStringList(phase + ".messages");
        if (index < 0 || index >= lines.size()) return;
        lines.remove(index);
        config.set(phase + ".messages", lines);
        save();
    }

    /** Returns a debug summary of a phase for display in chat. */
    public List<String> getSummary(String phase) {
        return List.of(
                "§6Phase: §e" + phase,
                "§7Duration: §e" + config.getInt(phase + ".duration-seconds", -1) + "s",
                "§7Title: §f" + config.getString(phase + ".title", "(none)"),
                "§7Subtitle: §f" + config.getString(phase + ".subtitle", "(none)"),
                "§7Messages (" + src(phase + ".messages").getStringList(phase + ".messages").size() + "):"
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void save() {
        try { config.save(file); }
        catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed to save ceremonies.yml", e); }
    }

    private String applyPlaceholders(String text, Map<String, String> ph) {
        if (ph == null) return text;
        for (var entry : ph.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }

    private String applyColor(String text) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }
}
