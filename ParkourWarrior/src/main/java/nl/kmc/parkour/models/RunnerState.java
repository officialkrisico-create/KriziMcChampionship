package nl.kmc.parkour.models;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player run state.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Highest checkpoint reached (== current respawn)</li>
 *   <li>Total points earned (sum of all reached checkpoint values, minus skipped ones)</li>
 *   <li>Death count, fail count for the current stage (skip availability)</li>
 *   <li>Set of skipped checkpoints (no points awarded for these)</li>
 *   <li>Whether the player has completed the full course</li>
 *   <li>Time of completion (for tiebreakers)</li>
 * </ul>
 */
public class RunnerState {

    private final UUID   uuid;
    private final String name;

    /** Highest checkpoint index reached (0 = start, no checkpoints hit). */
    private int highestCheckpoint;

    /** Number of times the player has died on the current stage. */
    private int currentStageFailCount;

    /** Total deaths this run. */
    private int totalDeaths;

    /** Skipped checkpoint indices — no points awarded for these. */
    private final Set<Integer> skippedCheckpoints = new HashSet<>();

    /** Total points earned (excluding skips). */
    private int totalPoints;

    private boolean finished;
    private long    finishTimeMs = -1;
    private int     placement = -1;

    private final long startTimeMs;

    public RunnerState(UUID uuid, String name) {
        this.uuid        = uuid;
        this.name        = name;
        this.startTimeMs = System.currentTimeMillis();
    }

    // ---- Checkpoint tracking --------------------------------------

    /**
     * Marks a checkpoint as reached. Idempotent — re-reaching a
     * previously-hit checkpoint doesn't double-award points.
     *
     * @return true if this was a NEW checkpoint (transition), false if already reached
     */
    public boolean reachCheckpoint(int index, int points) {
        if (index <= highestCheckpoint) return false;
        if (skippedCheckpoints.contains(index)) {
            // Player skipped, then somehow came back and hit it — count it now
            skippedCheckpoints.remove(index);
        }
        highestCheckpoint     = index;
        currentStageFailCount = 0;
        totalPoints          += points;
        return true;
    }

    /** Marks a checkpoint as skipped — no points. */
    public void skipCheckpoint(int index) {
        if (index <= highestCheckpoint) return;
        skippedCheckpoints.add(index);
        highestCheckpoint     = index;
        currentStageFailCount = 0;
    }

    public void recordDeath() {
        totalDeaths++;
        currentStageFailCount++;
    }

    public void markFinished(int placement) {
        if (finished) return;
        this.finished     = true;
        this.placement    = placement;
        this.finishTimeMs = System.currentTimeMillis();
    }

    // ---- Queries ---------------------------------------------------

    public UUID    getUuid()                  { return uuid; }
    public String  getName()                  { return name; }
    public int     getHighestCheckpoint()     { return highestCheckpoint; }
    public int     getCurrentStageFailCount() { return currentStageFailCount; }
    public int     getTotalDeaths()           { return totalDeaths; }
    public int     getTotalPoints()           { return totalPoints; }
    public boolean isFinished()               { return finished; }
    public long    getFinishTimeMs()          { return finishTimeMs; }
    public int     getPlacement()             { return placement; }
    public long    getStartTimeMs()           { return startTimeMs; }
    public Set<Integer> getSkippedCheckpoints() { return skippedCheckpoints; }

    public long getRunDurationMs() {
        if (finishTimeMs > 0) return finishTimeMs - startTimeMs;
        return System.currentTimeMillis() - startTimeMs;
    }
}
