package nl.kmc.adventure.models;

import java.util.UUID;

/**
 * Holds per-player state during an active race:
 * lap count, current lap start time, best lap time, total time,
 * and finish time.
 */
public class RacerData {

    private final UUID uuid;
    private final String name;

    /** System millis when the race began (same for all racers). */
    private long raceStart;

    /** System millis when current lap started (set on startline cross). */
    private long currentLapStart;

    /** Total laps completed. */
    private int lapsCompleted;

    /** Best lap time in millis (0 = no lap finished). */
    private long bestLapMs;

    /** Current lap time in millis — updated live. */
    private long currentLapMs;

    /** System millis when player crossed the final finish line. */
    private long finishTimeMs = -1;

    /** Final placement (1 = 1st). -1 = still racing. */
    private int placement = -1;

    /** Has the player actually started (crossed startline first time)? */
    private boolean started;

    public RacerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ---- Lifecycle -------------------------------------------------

    public void markRaceStart(long now) {
        this.raceStart = now;
    }

    public void startFirstLap(long now) {
        if (started) return;
        this.started = true;
        this.currentLapStart = now;
    }

    /**
     * Completes current lap.
     *
     * @param now current time millis
     * @return lap time in ms, or -1 if lap wasn't started
     */
    public long completeLap(long now) {
        if (!started) return -1;
        long lapTime = now - currentLapStart;
        lapsCompleted++;
        if (bestLapMs == 0 || lapTime < bestLapMs) bestLapMs = lapTime;
        currentLapStart = now;
        currentLapMs = 0;
        return lapTime;
    }

    /** Called every tick by RaceManager to keep currentLapMs fresh. */
    public void tickUpdate(long now) {
        if (!started || finishTimeMs > 0) return;
        currentLapMs = now - currentLapStart;
    }

    public void markFinished(long now, int placement) {
        this.finishTimeMs = now;
        this.placement = placement;
    }

    // ---- Getters ---------------------------------------------------

    public UUID    getUuid()            { return uuid; }
    public String  getName()            { return name; }
    public int     getLapsCompleted()   { return lapsCompleted; }
    public long    getBestLapMs()       { return bestLapMs; }
    public long    getCurrentLapMs()    { return currentLapMs; }
    public long    getFinishTimeMs()    { return finishTimeMs; }
    public int     getPlacement()       { return placement; }
    public boolean hasStarted()         { return started; }
    public boolean hasFinished()        { return finishTimeMs > 0; }
    public long    getTotalTimeMs()     { return finishTimeMs > 0 ? (finishTimeMs - raceStart) : 0; }

    // ---- Formatting ------------------------------------------------

    /** Formats a millisecond duration as mm:ss.SSS */
    public static String formatMs(long ms) {
        if (ms <= 0) return "--:--.---";
        long m = ms / 60_000;
        long s = (ms % 60_000) / 1000;
        long hundredths = (ms % 1000) / 10;
        return String.format("%02d:%02d.%02d", m, s, hundredths);
    }
}
