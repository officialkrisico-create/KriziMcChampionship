package nl.kmc.hitw.models;

import java.util.UUID;

/** Everything HITW tracks per player, across rounds. */
public final class PlayerState {

    private final UUID uuid;
    private final String name;

    // ── This round ────────────────────────────────────────────────────────────
    private boolean alive;
    private long    survivalMs;
    private int     placement;
    private int     wallsCleared;
    private boolean milestoneFired;

    /** Position at the end of the previous tick — the start of this tick's collision sweep. */
    private double prevX, prevY, prevZ;
    private boolean hasPrev;

    // ── Whole game ────────────────────────────────────────────────────────────
    private int  totalPoints;
    private long totalSurvivalMs;
    private int  roundsWon;
    private int  roundsPlayed;
    private int  bestWalls;

    public PlayerState(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    /** Ids of the walls that have hit (and shoved) this player this round. */
    private final java.util.Set<Integer> hitByWalls = new java.util.HashSet<>();

    public void startRound() {
        alive = true; survivalMs = 0; placement = 0; wallsCleared = 0; milestoneFired = false; hasPrev = false;
        hitByWalls.clear();
    }

    /** @return true the first time this wall hits the player (so feedback isn't repeated every tick) */
    public boolean markHitBy(int wallId) { return hitByWalls.add(wallId); }

    public boolean wasHitBy(int wallId) { return hitByWalls.contains(wallId); }

    public void eliminate(long survivalMs, int placement) {
        this.alive = false; this.survivalMs = survivalMs; this.placement = placement;
    }

    /** Called for survivors when the round ends: they lasted the whole round. */
    public void survive(long survivalMs, int placement) {
        this.survivalMs = survivalMs; this.placement = placement;
    }

    public void finishRound(int pointsAwarded, boolean won) {
        totalPoints += pointsAwarded;
        totalSurvivalMs += survivalMs;
        roundsPlayed++;
        if (won) roundsWon++;
        bestWalls = Math.max(bestWalls, wallsCleared);
    }

    public void rememberPosition(double x, double y, double z) { prevX = x; prevY = y; prevZ = z; hasPrev = true; }

    public UUID    getUuid()            { return uuid; }
    public String  getName()            { return name; }
    public boolean isAlive()            { return alive; }
    public long    getSurvivalMs()      { return survivalMs; }
    public int     getPlacement()       { return placement; }
    public int     getWallsCleared()    { return wallsCleared; }
    public void    addWallCleared()     { wallsCleared++; }
    public boolean isMilestoneFired()   { return milestoneFired; }
    public void    markMilestoneFired() { milestoneFired = true; }
    public boolean hasPrev()            { return hasPrev; }
    public double  getPrevX()           { return prevX; }
    public double  getPrevY()           { return prevY; }
    public double  getPrevZ()           { return prevZ; }
    public int     getTotalPoints()     { return totalPoints; }
    public long    getTotalSurvivalMs() { return totalSurvivalMs; }
    public int     getRoundsWon()       { return roundsWon; }
    public int     getRoundsPlayed()    { return roundsPlayed; }
    public int     getBestWalls()       { return bestWalls; }
}
