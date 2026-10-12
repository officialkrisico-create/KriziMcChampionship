package nl.kmc.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-wide "dry-run" switch: while it is on, a game plays out normally but nothing it does may change
 * the tournament — no points, kills, wins, medals, MVP records, momentum or Elo.
 *
 * <p>Set by KMCCore's director when an admin starts a game with {@code /kmcauto dryrun <game>}. Every
 * place that persists a result checks {@link #active()} first, and the director additionally restores a
 * snapshot at the end as a safety net. Read from the main thread only, but kept thread-safe anyway.
 */
public final class DryRun {

    private static volatile String gameId;
    private static final AtomicInteger SUPPRESSED = new AtomicInteger();

    private DryRun() {}

    public static void begin(String id) { gameId = id; SUPPRESSED.set(0); }

    public static void end() { gameId = null; }

    public static boolean active() { return gameId != null; }

    /** The game being dry-run, or {@code null}. */
    public static String gameId() { return gameId; }

    /** Counts a suppressed reward so the admin can see how many writes the dry-run blocked. */
    public static void suppressed() { SUPPRESSED.incrementAndGet(); }

    public static int suppressedCount() { return SUPPRESSED.get(); }
}
