package nl.kmc.parkour.models;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player run state.
 *
 * <p>Tracks:
 * <ul>
 *   <li>Highest <b>stage</b> completed (gates progress through the course)</li>
 *   <li>Index of the last reached checkpoint (== current respawn target)</li>
 *   <li>Set of stages completed (for skip checks and progress display)</li>
 *   <li>Total points earned (with difficulty multipliers applied)</li>
 *   <li>Death count, fail count for the current stage (skip availability)</li>
 *   <li>Set of skipped stages (no points awarded for these)</li>
 *   <li>Whether the player has completed the full course</li>
 *   <li>Time of completion (for tiebreakers)</li>
 * </ul>
 *
 * <p>For backwards compatibility, {@code highestCheckpoint} is kept as
 * an alias and refers to the index of the last checkpoint physically
 * touched.
 */
public class RunnerState {

    private final UUID   uuid;
    private final String name;

    /** Highest STAGE completed (0 = none yet). */
    private int highestStage;

    /** Index of the last checkpoint physically touched (== respawn target). */
    private int lastCheckpointIndex;

    /** Stages completed (for fast progress queries). */
    private final Set<Integer> stagesReached = new HashSet<>();

    /** Number of times the player has died ON THE CURRENT STAGE. */
    private int currentStageFailCount;

    /** Total deaths this run. */
    private int totalDeaths;

    /** Stages skipped via /pkw skip — no points awarded. */
    private final Set<Integer> skippedStages = new HashSet<>();

    /** Total points earned (already includes difficulty multiplier). */
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
     * Marks a checkpoint as reached. Idempotent — re-reaching the same
     * stage doesn't double-award.
     *
     * @param index    the checkpoint's globally-unique index
     * @param stage    the checkpoint's stage number
     * @param awardedPoints points to add (already multiplied by difficulty)
     * @return true if this advanced the player to a NEW stage
     */
    public boolean reachCheckpoint(int index, int stage, int awardedPoints) {
        // The respawn target always shifts to the most recent CP touched
        lastCheckpointIndex = index;

        if (stage <= highestStage) return false;       // already past this stage
        if (stagesReached.contains(stage)) return false;

        stagesReached.add(stage);
        highestStage          = stage;
        currentStageFailCount = 0;
        totalPoints          += awardedPoints;
        skippedStages.remove(stage);
        return true;
    }

    /** Marks a stage as skipped — no points. */
    public void skipStage(int stage) {
        if (stage <= highestStage) return;
        skippedStages.add(stage);
        stagesReached.add(stage);
        highestStage          = stage;
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
    public int     getHighestStage()          { return highestStage; }
    public int     getLastCheckpointIndex()   { return lastCheckpointIndex; }
    /** Legacy alias — returns the LAST checkpoint touched, not the highest STAGE. */
    public int     getHighestCheckpoint()     { return lastCheckpointIndex; }
    public int     getCurrentStageFailCount() { return currentStageFailCount; }
    public int     getTotalDeaths()           { return totalDeaths; }
    public int     getTotalPoints()           { return totalPoints; }
    public boolean isFinished()               { return finished; }
    public long    getFinishTimeMs()          { return finishTimeMs; }
    public int     getPlacement()             { return placement; }
    public long    getStartTimeMs()           { return startTimeMs; }
    public Set<Integer> getStagesReached()    { return stagesReached; }
    public Set<Integer> getSkippedStages()    { return skippedStages; }
    /** Legacy alias — kept for any consumers using the old name. */
    public Set<Integer> getSkippedCheckpoints() { return skippedStages; }

    public long getRunDurationMs() {
        if (finishTimeMs > 0) return finishTimeMs - startTimeMs;
        return System.currentTimeMillis() - startTimeMs;
    }
}
