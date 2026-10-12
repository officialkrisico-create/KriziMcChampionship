package nl.kmc.kmccore.scoring;

import nl.kmc.kmccore.KMCCore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * The tournament's book of every point award — who got how many points, in which game, and why.
 *
 * <p>Every award goes through {@link nl.kmc.kmccore.managers.PointsManager}, which books it here. That is
 * what makes the post-game score breakdown, the "your game" summary, the awards and the statsbook possible,
 * and what lets a rerun roll the book back together with the points.
 *
 * <p>Also keeps the <em>highlights</em> games report for the end-of-event awards (clutch saves, race
 * results, ...). Persisted to {@code ledger.csv} (and {@code highlights.csv}) so a crash does not lose the
 * evening; cleared when a tournament starts and archived when it ends.
 */
public final class PointsLedger {

    /** One booked award. {@code points} is what was actually credited (after multiplier and comeback bonus). */
    public record Entry(long time, int round, int gameSeq, String gameId, String reason,
                        UUID player, String playerName, String teamId,
                        int base, double multiplier, int comeback, int points, boolean teamOnly) {}

    /** One game of the tournament, in the order it was launched. */
    public record GameInfo(int seq, String gameId, int round, long startedMs) {}

    /** A notable performance reported by a game (for the awards). */
    public record Highlight(String category, UUID player, String playerName, String teamId,
                            int value, String gameId, int gameSeq) {}

    private final KMCCore plugin;   // may be null in unit tests
    private final File file;
    private final File highlightFile;

    private final List<Entry> entries = new ArrayList<>();
    private final List<GameInfo> games = new ArrayList<>();
    private final List<Highlight> highlights = new ArrayList<>();
    private int gameSeq;
    private boolean dirty;

    public PointsLedger(KMCCore plugin) {
        this.plugin = plugin;
        File dir = plugin != null ? plugin.getDataFolder() : new File(".");
        this.file = new File(dir, "ledger.csv");
        this.highlightFile = new File(dir, "highlights.csv");
        if (plugin != null) load();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /** A new game was launched: later awards belong to it. */
    public synchronized int beginGame(String gameId, int round) {
        gameSeq++;
        games.add(new GameInfo(gameSeq, gameId, round, System.currentTimeMillis()));
        dirty = true;
        return gameSeq;
    }

    public synchronized void record(Entry e) {
        entries.add(e);
        dirty = true;
    }

    public synchronized void recordHighlight(Highlight h) {
        highlights.add(h);
        dirty = true;
    }

    /** Rolls the book back (rerun): drops everything booked after the given marks. */
    public synchronized void truncate(int entryCount, int highlightCount, int lastGameSeq) {
        while (entries.size() > Math.max(0, entryCount)) entries.remove(entries.size() - 1);
        while (highlights.size() > Math.max(0, highlightCount)) highlights.remove(highlights.size() - 1);
        games.removeIf(g -> g.seq() > lastGameSeq);
        gameSeq = Math.min(gameSeq, lastGameSeq);
        dirty = true;
    }

    public synchronized void clear() {
        entries.clear();
        games.clear();
        highlights.clear();
        gameSeq = 0;
        dirty = true;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public synchronized List<Entry> all()            { return List.copyOf(entries); }
    public synchronized List<GameInfo> games()       { return List.copyOf(games); }
    public synchronized List<Highlight> highlights() { return List.copyOf(highlights); }
    public synchronized int size()                   { return entries.size(); }
    public synchronized int highlightCount()         { return highlights.size(); }
    public synchronized int lastGameSeq()            { return gameSeq; }

    public synchronized List<Entry> forGame(int seq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.gameSeq() == seq) out.add(e);
        return out;
    }

    public synchronized List<Entry> forPlayer(UUID player) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (player.equals(e.player())) out.add(e);
        return out;
    }

    public synchronized GameInfo game(int seq) {
        for (GameInfo g : games) if (g.seq() == seq) return g;
        return null;
    }

    // ── Aggregation helpers ───────────────────────────────────────────────────

    /** Points per reason, biggest first. */
    public static LinkedHashMap<String, Integer> byReason(Collection<Entry> in) {
        Map<String, Integer> sum = new HashMap<>();
        for (Entry e : in) sum.merge(e.reason(), e.points(), Integer::sum);
        return sortedDesc(sum);
    }

    /** Points per player (team-only awards excluded), biggest first. */
    public static LinkedHashMap<UUID, Integer> byPlayer(Collection<Entry> in) {
        Map<UUID, Integer> sum = new HashMap<>();
        for (Entry e : in) if (e.player() != null) sum.merge(e.player(), e.points(), Integer::sum);
        return sortedDesc(sum);
    }

    /** Points per team including team-only awards, biggest first. */
    public static LinkedHashMap<String, Integer> byTeam(Collection<Entry> in) {
        Map<String, Integer> sum = new HashMap<>();
        for (Entry e : in) if (e.teamId() != null) sum.merge(e.teamId(), e.points(), Integer::sum);
        return sortedDesc(sum);
    }

    public static <K> LinkedHashMap<K, Integer> sortedDesc(Map<K, Integer> map) {
        List<Map.Entry<K, Integer>> l = new ArrayList<>(map.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        LinkedHashMap<K, Integer> out = new LinkedHashMap<>();
        for (var e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Writes the book to disk if anything changed (call from an async task or on shutdown). */
    public void flush() {
        List<Entry> e; List<Highlight> h; List<GameInfo> g;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            e = new ArrayList<>(entries);
            h = new ArrayList<>(highlights);
            g = new ArrayList<>(games);
        }
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                for (GameInfo gi : g) w.write("G;" + gi.seq() + ";" + esc(gi.gameId()) + ";" + gi.round() + ";" + gi.startedMs() + "\n");
                for (Entry x : e) {
                    w.write("E;" + x.time() + ";" + x.round() + ";" + x.gameSeq() + ";" + esc(x.gameId()) + ";"
                            + esc(x.reason()) + ";" + (x.player() != null ? x.player() : "") + ";" + esc(x.playerName())
                            + ";" + esc(x.teamId()) + ";" + x.base() + ";" + x.multiplier() + ";" + x.comeback()
                            + ";" + x.points() + ";" + (x.teamOnly() ? 1 : 0) + "\n");
                }
            }
            try (BufferedWriter w = Files.newBufferedWriter(highlightFile.toPath(), StandardCharsets.UTF_8)) {
                for (Highlight x : h) {
                    w.write(esc(x.category()) + ";" + (x.player() != null ? x.player() : "") + ";" + esc(x.playerName())
                            + ";" + esc(x.teamId()) + ";" + x.value() + ";" + esc(x.gameId()) + ";" + x.gameSeq() + "\n");
                }
            }
        } catch (IOException ex) {
            if (plugin != null) plugin.getLogger().warning("Could not write the points ledger: " + ex.getMessage());
        }
    }

    private void load() {
        try {
            if (file.isFile()) {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    String[] p = line.split(";", -1);
                    if (p.length >= 5 && p[0].equals("G")) {
                        int seq = Integer.parseInt(p[1]);
                        games.add(new GameInfo(seq, un(p[2]), Integer.parseInt(p[3]), Long.parseLong(p[4])));
                        gameSeq = Math.max(gameSeq, seq);
                    } else if (p.length >= 14 && p[0].equals("E")) {
                        entries.add(new Entry(Long.parseLong(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                                un(p[4]), un(p[5]), p[6].isEmpty() ? null : UUID.fromString(p[6]), un(p[7]), un(p[8]),
                                Integer.parseInt(p[9]), Double.parseDouble(p[10]), Integer.parseInt(p[11]),
                                Integer.parseInt(p[12]), "1".equals(p[13])));
                    }
                }
            }
            if (highlightFile.isFile()) {
                for (String line : Files.readAllLines(highlightFile.toPath(), StandardCharsets.UTF_8)) {
                    String[] p = line.split(";", -1);
                    if (p.length < 7) continue;
                    highlights.add(new Highlight(un(p[0]), p[1].isEmpty() ? null : UUID.fromString(p[1]), un(p[2]),
                            un(p[3]), Integer.parseInt(p[4]), un(p[5]), Integer.parseInt(p[6])));
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read the points ledger (starting empty): " + ex.getMessage());
            entries.clear(); games.clear(); highlights.clear(); gameSeq = 0;
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace(";", ",").replace("\n", " "); }
    private static String un(String s)  { return s == null || s.isEmpty() ? null : s; }
}
