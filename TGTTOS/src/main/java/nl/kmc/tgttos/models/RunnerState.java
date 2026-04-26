package nl.kmc.tgttos.models;

import java.util.HashMap;
import java.util.UUID;

/**
 * Per-player state across the whole TGTTOS game (multiple rounds).
 *
 * <p>Tracks per-round placement (1, 2, 3, ...) and total cumulative
 * points across all rounds. Round placements are awarded points
 * based on the points config (1st, 2nd, 3rd, finished, dnf).
 */
public class RunnerState {

    private final UUID   uuid;
    private final String name;

    /** Round number → finishing position (1-based). 0 = didn't finish. */
    private final java.util.Map<Integer, Integer> roundPlacements = new HashMap<>();

    /** Cumulative total points across all rounds. */
    private int totalPoints;

    /** Total deaths across all rounds. */
    private int totalDeaths;

    /** Status for the CURRENT round only — reset at round start. */
    private boolean currentRoundFinished;
    private int     currentRoundDeaths;

    public RunnerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID    getUuid()        { return uuid; }
    public String  getName()         { return name; }
    public int     getTotalPoints()  { return totalPoints; }
    public int     getTotalDeaths()  { return totalDeaths; }
    public java.util.Map<Integer, Integer> getRoundPlacements() {
        return java.util.Collections.unmodifiableMap(roundPlacements);
    }
    public boolean isCurrentRoundFinished() { return currentRoundFinished; }
    public int     getCurrentRoundDeaths()  { return currentRoundDeaths; }

    public int getRoundPlacement(int round) {
        return roundPlacements.getOrDefault(round, 0);
    }

    public int getRoundsFinished() {
        int count = 0;
        for (int p : roundPlacements.values()) if (p > 0) count++;
        return count;
    }

    // ---- Round lifecycle ----

    public void startRound() {
        currentRoundFinished = false;
        currentRoundDeaths   = 0;
    }

    public void recordDeath() {
        currentRoundDeaths++;
        totalDeaths++;
    }

    public void finishRound(int round, int placement, int pointsAwarded) {
        roundPlacements.put(round, placement);
        currentRoundFinished = true;
        totalPoints += pointsAwarded;
    }

    public void giveBonusPoints(int amount) {
        totalPoints += amount;
    }
}
