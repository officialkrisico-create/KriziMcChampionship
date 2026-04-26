package nl.kmc.elytra.models;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player elytra-course state.
 *
 * <p>Tracks for both race and collection modes:
 * <ul>
 *   <li>Set of checkpoints reached (collection mode = points pile up;
 *       race mode = used to detect when finish is reached)</li>
 *   <li>Highest checkpoint index reached (race mode respawn target)</li>
 *   <li>Total points earned</li>
 *   <li>Crash count (telemetry / skip mechanic)</li>
 *   <li>Finish placement + time (race mode tiebreaker)</li>
 * </ul>
 */
public class RunnerState {

    private final UUID   uuid;
    private final String name;

    private final Set<Integer> reachedCheckpoints = new HashSet<>();

    /** Highest checkpoint index reached — used for race-mode respawn. */
    private int    highestCheckpoint;
    private int    totalPoints;
    private int    crashes;
    private boolean finished;
    private long   finishTimeMs = -1;
    private int    placement = -1;

    private final long startTimeMs;

    public RunnerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Record a checkpoint hit. Returns true if this checkpoint is new
     * (was not previously reached), false if already collected.
     */
    public boolean reach(int index, int points) {
        if (!reachedCheckpoints.add(index)) return false;
        if (index > highestCheckpoint) highestCheckpoint = index;
        totalPoints += points;
        return true;
    }

    public void recordCrash() { crashes++; }

    public void markFinished(int placement) {
        if (finished) return;
        this.finished = true;
        this.placement = placement;
        this.finishTimeMs = System.currentTimeMillis();
    }

    public UUID    getUuid()                  { return uuid; }
    public String  getName()                  { return name; }
    public Set<Integer> getReachedCheckpoints() { return reachedCheckpoints; }
    public int     getHighestCheckpoint()     { return highestCheckpoint; }
    public int     getTotalPoints()           { return totalPoints; }
    public int     getCrashes()               { return crashes; }
    public boolean isFinished()               { return finished; }
    public long    getFinishTimeMs()          { return finishTimeMs; }
    public int     getPlacement()             { return placement; }
    public long    getStartTimeMs()           { return startTimeMs; }

    public long    getRunDurationMs() {
        if (finishTimeMs > 0) return finishTimeMs - startTimeMs;
        return System.currentTimeMillis() - startTimeMs;
    }
}
