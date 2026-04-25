package nl.kmc.adventure.models;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player race state.
 *
 * <p>NEW: tracks which checkpoints the player has passed through during
 * the current lap. They must hit ALL checkpoints in order before crossing
 * the finish line counts as a lap completion.
 */
public class RacerData {

    private final UUID uuid;
    private final String name;

    private long raceStart;
    private long currentLapStart;

    private int  lapsCompleted;
    private long bestLapMs;
    private long currentLapMs;
    private long finishTimeMs = -1;
    private int  placement = -1;
    private boolean started;

    /** Checkpoints reached on the CURRENT lap (cleared after each lap). */
    private final Set<Integer> checkpointsThisLap = new HashSet<>();

    /** Highest checkpoint index reached this lap (for "skipped checkpoints" detection). */
    private int lastCheckpointIndex = 0;

    public RacerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ---- Lifecycle -------------------------------------------------

    public void markRaceStart(long now) { this.raceStart = now; }

    public void startFirstLap(long now) {
        if (started) return;
        this.started = true;
        this.currentLapStart = now;
        this.checkpointsThisLap.clear();
        this.lastCheckpointIndex = 0;
    }

    /**
     * Completes a lap. Resets checkpoint tracking for the next lap.
     *
     * @return lap time in ms, or -1 if not started
     */
    public long completeLap(long now) {
        if (!started) return -1;
        long lapTime = now - currentLapStart;
        lapsCompleted++;
        if (bestLapMs == 0 || lapTime < bestLapMs) bestLapMs = lapTime;
        currentLapStart = now;
        currentLapMs = 0;
        checkpointsThisLap.clear();
        lastCheckpointIndex = 0;
        return lapTime;
    }

    public void tickUpdate(long now) {
        if (!started || finishTimeMs > 0) return;
        currentLapMs = now - currentLapStart;
    }

    public void markFinished(long now, int placement) {
        this.finishTimeMs = now;
        this.placement = placement;
    }

    // ---- Checkpoints -----------------------------------------------

    /**
     * Records a checkpoint hit.
     *
     * @param index the 1-indexed checkpoint number
     * @return CheckpointResult enum describing what happened
     */
    public CheckpointResult passCheckpoint(int index) {
        if (checkpointsThisLap.contains(index)) {
            return CheckpointResult.ALREADY_PASSED;
        }
        // Must be reached in order — skipping ahead is forbidden
        if (index != lastCheckpointIndex + 1) {
            return CheckpointResult.OUT_OF_ORDER;
        }
        checkpointsThisLap.add(index);
        lastCheckpointIndex = index;
        return CheckpointResult.OK;
    }

    /** Have all required checkpoints been reached this lap? */
    public boolean hasAllCheckpoints(int totalCheckpoints) {
        return lastCheckpointIndex >= totalCheckpoints;
    }

    public int getLastCheckpointIndex() { return lastCheckpointIndex; }
    public Set<Integer> getCheckpointsThisLap() { return checkpointsThisLap; }

    public enum CheckpointResult { OK, ALREADY_PASSED, OUT_OF_ORDER }

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

    public static String formatMs(long ms) {
        if (ms <= 0) return "--:--.---";
        long m = ms / 60_000;
        long s = (ms % 60_000) / 1000;
        long hundredths = (ms % 1000) / 10;
        return String.format("%02d:%02d.%02d", m, s, hundredths);
    }
}
