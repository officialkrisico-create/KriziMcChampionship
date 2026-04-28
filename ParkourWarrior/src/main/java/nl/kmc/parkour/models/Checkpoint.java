package nl.kmc.parkour.models;

import org.bukkit.Location;

/**
 * A single checkpoint in the parkour course.
 *
 * <p>Each checkpoint has:
 * <ul>
 *   <li>An <b>index</b> — globally unique 1-based identifier (used in commands and config keys)</li>
 *   <li>A <b>stage</b> — ordering group. Multiple checkpoints can share a stage as
 *       difficulty alternatives. If unspecified, defaults to the index value (so
 *       legacy linear courses still work).</li>
 *   <li>A <b>difficulty</b> — MAIN (default for non-branching CPs), EASY, MEDIUM, HARD</li>
 *   <li>A 2-corner trigger region</li>
 *   <li>A respawn location</li>
 *   <li>A base point value (multiplied by difficulty.multiplier on award)</li>
 *   <li>An optional display name</li>
 * </ul>
 */
public class Checkpoint {

    private final int        index;
    private final int        stage;
    private final Difficulty difficulty;
    private final Location   pos1;
    private final Location   pos2;
    private final Location   respawn;
    private final int        points;
    private final String     displayName;

    /** Legacy 6-arg constructor: stage = index, difficulty = MAIN. */
    public Checkpoint(int index, Location pos1, Location pos2, Location respawn,
                      int points, String displayName) {
        this(index, index, Difficulty.MAIN, pos1, pos2, respawn, points, displayName);
    }

    /** Full constructor including stage + difficulty. */
    public Checkpoint(int index, int stage, Difficulty difficulty,
                      Location pos1, Location pos2, Location respawn,
                      int points, String displayName) {
        this.index       = index;
        this.stage       = stage;
        this.difficulty  = difficulty != null ? difficulty : Difficulty.MAIN;
        this.pos1        = pos1;
        this.pos2        = pos2;
        this.respawn     = respawn;
        this.points      = points;
        this.displayName = displayName;
    }

    public int        getIndex()       { return index; }
    public int        getStage()       { return stage; }
    public Difficulty getDifficulty()  { return difficulty; }
    public Location   getPos1()        { return pos1; }
    public Location   getPos2()        { return pos2; }
    public Location   getRespawn()     { return respawn; }
    public int        getPoints()      { return points; }
    public String     getDisplayName() { return displayName; }

    /** Points actually awarded after the difficulty multiplier. */
    public int getAwardedPoints() {
        return (int) Math.round(points * difficulty.getMultiplier());
    }

    /** True if this CP is a difficulty-branch alternative (not MAIN). */
    public boolean isBranchOption() {
        return difficulty != Difficulty.MAIN;
    }

    /** Is the given location inside this checkpoint's trigger box? */
    public boolean contains(Location loc) {
        if (loc == null || pos1 == null || pos2 == null) return false;
        if (!loc.getWorld().equals(pos1.getWorld())) return false;

        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double minY = Math.min(pos1.getY(), pos2.getY());
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
}
