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

    /** All known phase keys (matches ceremonies.yml top-level keys). */
    public static final List<String> PHASES = List.of(
            "opening", "team-showcase", "tournament-overview",
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
        plugin.getLogger().info("[CeremonyManager] Loaded ceremonies.yml.");
    }

    /** Duration in seconds for a phase (falls back to provided default if not set). */
    public int getDuration(String phase, int defaultSec) {
        return config.getInt(phase + ".duration-seconds", defaultSec);
    }

    /**
     * Returns the messages list for a phase, with placeholders replaced.
     * Returns an empty list if none are configured.
     */
    public List<String> getMessages(String phase, Map<String, String> placeholders) {
        List<String> raw = config.getStringList(phase + ".messages");
        return raw.stream()
                .map(line -> applyColor(applyPlaceholders(line, placeholders)))
                .toList();
    }

    /** Title text for the phase, or empty string if not configured. */
    public String getTitle(String phase, Map<String, String> placeholders) {
        String raw = config.getString(phase + ".title", "");
        return applyColor(applyPlaceholders(raw, placeholders));
    }

    /** Subtitle text for the phase, or empty string if not configured. */
    public String getSubtitle(String phase, Map<String, String> placeholders) {
        String raw = config.getString(phase + ".subtitle", "");
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
        List<String> lines = config.getStringList(phase + ".messages");
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
        List<String> lines = config.getStringList(phase + ".messages");
        if (index < 0 || index >= lines.size()) return;
        lines.set(index, message);
        config.set(phase + ".messages", lines);
        save();
    }

    /** Removes a specific message line (0-indexed) from a phase and saves. */
    public void removeMessage(String phase, int index) {
        List<String> lines = config.getStringList(phase + ".messages");
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
                "§7Messages (" + config.getStringList(phase + ".messages").size() + "):"
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
