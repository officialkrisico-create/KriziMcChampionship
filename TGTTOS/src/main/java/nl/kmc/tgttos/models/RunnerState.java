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

    // ---- DNF / finish statistics ----

    private int dnfCount;
    private int consecutiveDnf;

    public int getDnfCount()        { return dnfCount; }
    public int getConsecutiveDnf()  { return consecutiveDnf; }
    public int getMapsFinished()    { return getRoundsFinished(); }
    public int getMapsPlayed()      { return roundPlacements.size(); }
    public int getMapsWon()         { int c = 0; for (int p : roundPlacements.values()) if (p == 1) c++; return c; }
    public double getFinishRate()   { int played = getMapsPlayed(); return played == 0 ? 0 : (double) getMapsFinished() / played; }
    public double getDnfPercent()   { int played = getMapsPlayed(); return played == 0 ? 0 : 100.0 * dnfCount / played; }
    public double getAveragePlacement() {
        int sum = 0, n = 0;
        for (int p : roundPlacements.values()) if (p > 0) { sum += p; n++; }
        return n == 0 ? 0 : (double) sum / n;
    }

    /** Marks this map as Did-Not-Finish: no placement, no points. */
    public void recordDnf(int map) {
        roundPlacements.put(map, 0);   // 0 = DNF
        currentRoundFinished = false;
        dnfCount++;
        consecutiveDnf++;
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
        consecutiveDnf = 0;   // a finish resets the DNF streak
    }

    public void giveBonusPoints(int amount) {
        totalPoints += amount;
    }
}
