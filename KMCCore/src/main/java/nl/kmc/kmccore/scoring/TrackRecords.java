package nl.kmc.kmccore.scoring;

import nl.kmc.kmccore.KMCCore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Track records ("sector records") and personal bests per track — the data behind Track Mastery.
 *
 * <p>A race game reports every finished sector / lap / run through {@code api.games().recordSector(...)}.
 * Sector {@code 0} is the whole run (lap or course); {@code 1..n} are the sectors. For every
 * game → track → sector we keep the overall record and each player's best.
 *
 * <p>Stored in {@code track-records.yml}. Records are all-time: they survive tournament resets.
 */
public final class TrackRecords {

    /** What a submitted time turned out to be. */
    public record Result(boolean personalBest, boolean trackRecord, long previousBestMs, long previousRecordMs,
                         String previousRecordHolder) {}

    /** One row of a ranking. */
    public record Time(UUID player, String name, long millis) {}

    private final KMCCore plugin;   // may be null (tests)
    private final File file;
    private YamlConfiguration cfg;
    private boolean dirty;

    public TrackRecords(KMCCore plugin) {
        this(plugin, new File(plugin.getDataFolder(), "track-records.yml"));
    }

    public TrackRecords(KMCCore plugin, File file) {
        this.plugin = plugin;
        this.file = file;
        this.cfg = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    public synchronized Result submit(String gameId, String trackId, int sector, UUID player, String name, long millis) {
        if (millis <= 0 || player == null) return new Result(false, false, -1, -1, null);
        String base = path(gameId, trackId, sector);
        long recordMs = cfg.getLong(base + ".record.ms", -1);
        String holder = cfg.getString(base + ".record.name");
        long bestMs = cfg.getLong(base + ".players." + player + ".ms", -1);

        boolean pb = bestMs < 0 || millis < bestMs;
        boolean rec = recordMs < 0 || millis < recordMs;
        if (pb) {
            cfg.set(base + ".players." + player + ".ms", millis);
            cfg.set(base + ".players." + player + ".name", name);
            cfg.set(base + ".players." + player + ".at", System.currentTimeMillis());
        }
        if (rec) {
            cfg.set(base + ".record.ms", millis);
            cfg.set(base + ".record.uuid", player.toString());
            cfg.set(base + ".record.name", name);
            cfg.set(base + ".record.at", System.currentTimeMillis());
        }
        if (pb || rec) dirty = true;
        return new Result(pb, rec, bestMs, recordMs, holder);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public synchronized Time record(String gameId, String trackId, int sector) {
        String base = path(gameId, trackId, sector);
        long ms = cfg.getLong(base + ".record.ms", -1);
        if (ms < 0) return null;
        String u = cfg.getString(base + ".record.uuid");
        return new Time(u != null ? UUID.fromString(u) : null, cfg.getString(base + ".record.name", "?"), ms);
    }

    public synchronized long best(String gameId, String trackId, int sector, UUID player) {
        return cfg.getLong(path(gameId, trackId, sector) + ".players." + player + ".ms", -1);
    }

    public synchronized List<Time> top(String gameId, String trackId, int sector, int limit) {
        List<Time> out = new ArrayList<>();
        ConfigurationSection s = cfg.getConfigurationSection(path(gameId, trackId, sector) + ".players");
        if (s == null) return out;
        for (String key : s.getKeys(false)) {
            try {
                out.add(new Time(UUID.fromString(key), s.getString(key + ".name", "?"), s.getLong(key + ".ms")));
            } catch (IllegalArgumentException ignored) { }
        }
        out.sort(Comparator.comparingLong(Time::millis));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Every (game, track) that has at least one time, in insertion order. */
    public synchronized List<String[]> tracks() {
        List<String[]> out = new ArrayList<>();
        ConfigurationSection games = cfg.getConfigurationSection("tracks");
        if (games == null) return out;
        for (String g : games.getKeys(false)) {
            ConfigurationSection tr = games.getConfigurationSection(g);
            if (tr == null) continue;
            for (String t : tr.getKeys(false)) out.add(new String[]{g, t});
        }
        return out;
    }

    /** Sector numbers (>= 1) that have times for a track, ascending. */
    public synchronized List<Integer> sectors(String gameId, String trackId) {
        List<Integer> out = new ArrayList<>();
        ConfigurationSection s = cfg.getConfigurationSection("tracks." + gameId + "." + trackId.replace('.', '_'));
        if (s == null) return out;
        for (String k : s.getKeys(false)) {
            try { int n = Integer.parseInt(k); if (n >= 1) out.add(n); } catch (NumberFormatException ignored) { }
        }
        Collections.sort(out);
        return out;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void flush() {
        String data;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            data = cfg.saveToString();
        }
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            java.nio.file.Files.writeString(file.toPath(), data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (plugin != null) plugin.getLogger().warning("Could not save track-records.yml: " + e.getMessage());
        }
    }

    private static String path(String gameId, String trackId, int sector) {
        return "tracks." + gameId + "." + trackId.replace('.', '_') + "." + sector;
    }

    /** {@code 83456} → {@code "1:23.456"}, {@code 9120} → {@code "9.120"}. */
    public static String format(long ms) {
        if (ms < 0) return "—";
        long min = ms / 60000, sec = (ms / 1000) % 60, milli = ms % 1000;
        return min > 0 ? String.format(Locale.ROOT, "%d:%02d.%03d", min, sec, milli)
                : String.format(Locale.ROOT, "%d.%03d", sec, milli);
    }
}
