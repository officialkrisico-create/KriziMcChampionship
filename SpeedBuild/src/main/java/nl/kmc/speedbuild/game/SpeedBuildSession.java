package nl.kmc.speedbuild.game;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player run through the 10 builds. Holds progression state, timing,
 * accumulated results, and the player's isolated slot geometry.
 */
public final class SpeedBuildSession {

    private final UUID   player;
    private final String name;
    private final int    slotIndex;        // determines this player's region offset

    private int  currentBuildIndex;        // 0-9
    private long startTime;                 // whole-session start (ms)
    private long buildStartTime;            // current build start (ms)
    private double totalScore;
    private boolean finished;

    private final List<BuildResult> results = new ArrayList<>();

    // Geometry of the CURRENT stage, set when a build is loaded.
    private Location buildMin;              // min corner of the build area
    private Location blueprintMin;          // min corner of the reference paste

    public SpeedBuildSession(UUID player, String name, int slotIndex) {
        this.player    = player;
        this.name      = name;
        this.slotIndex = slotIndex;
        this.startTime = System.currentTimeMillis();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public UUID    getPlayer()            { return player; }
    public String  getName()              { return name; }
    public int     getSlotIndex()         { return slotIndex; }
    public int     getCurrentBuildIndex() { return currentBuildIndex; }
    public long    getStartTime()         { return startTime; }
    public long    getBuildStartTime()    { return buildStartTime; }
    public double  getTotalScore()        { return totalScore; }
    public boolean isFinished()           { return finished; }
    public List<BuildResult> getResults() { return results; }

    public Location getBuildMin()     { return buildMin; }
    public Location getBlueprintMin() { return blueprintMin; }

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void setGeometry(Location buildMin, Location blueprintMin) {
        this.buildMin     = buildMin;
        this.blueprintMin = blueprintMin;
    }

    public void markBuildStart() { this.buildStartTime = System.currentTimeMillis(); }

    public void recordResult(BuildResult result) {
        results.add(result);
        totalScore += result.finalScore();
    }

    /** Advances to the next build; returns true if there is one, false if done. */
    public boolean advance() {
        currentBuildIndex++;
        return currentBuildIndex < 10;
    }

    public boolean isOnFinalBuild() { return currentBuildIndex >= 9; }

    public void finish() { this.finished = true; }

    // ── Derived stats (for the end summary) ───────────────────────────────────

    public double averageAccuracy() {
        if (results.isEmpty()) return 0;
        return results.stream().mapToDouble(BuildResult::accuracyPercent).average().orElse(0);
    }

    public BuildResult bestBuild() {
        return results.stream().max((a, b) -> Double.compare(a.finalScore(), b.finalScore())).orElse(null);
    }

    public BuildResult worstBuild() {
        return results.stream().min((a, b) -> Double.compare(a.finalScore(), b.finalScore())).orElse(null);
    }

    public double totalTimeSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }
}
