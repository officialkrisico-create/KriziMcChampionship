package nl.kmc.blockparty.models;

import java.util.UUID;

/**
 * Per-player runtime state for one Block Party match.
 * Tracks survival, clutch performance, and placement.
 */
public final class BPPlayer {

    private final UUID   uuid;
    private final String name;

    private boolean alive = true;
    private int     eliminatedRound = -1;   // -1 = still alive / winner
    private int     roundsSurvived;

    // Clutch tracking
    private int     clutches;
    private int     bestClutchStreak;
    private int     currentClutchStreak;
    private boolean clutchThisRound;
    private boolean wasOnTarget;             // last-tick "standing on correct colour"

    private int     chaosWins;               // rounds survived while a chaos event was active

    public BPPlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID    getUuid()            { return uuid; }
    public String  getName()            { return name; }
    public boolean isAlive()            { return alive; }
    public int     getEliminatedRound() { return eliminatedRound; }
    public int     getRoundsSurvived()  { return roundsSurvived; }
    public int     getClutches()        { return clutches; }
    public int     getBestClutchStreak(){ return bestClutchStreak; }
    public int     getChaosWins()       { return chaosWins; }
    public boolean isClutchThisRound()  { return clutchThisRound; }
    public boolean wasOnTarget()        { return wasOnTarget; }

    public void setWasOnTarget(boolean v) { this.wasOnTarget = v; }

    /** Called at the start of each round this player is alive for. */
    public void beginRound() {
        clutchThisRound = false;
        wasOnTarget     = false;
    }

    public void markClutch() {
        clutchThisRound = true;
    }

    /** Survived a round — bump counters and resolve any clutch streak. */
    public void surviveRound(int round, boolean chaosActive) {
        roundsSurvived = round;
        if (chaosActive) chaosWins++;
        if (clutchThisRound) {
            clutches++;
            currentClutchStreak++;
            bestClutchStreak = Math.max(bestClutchStreak, currentClutchStreak);
        } else {
            currentClutchStreak = 0;
        }
    }

    public void eliminate(int round) {
        this.alive           = false;
        this.eliminatedRound = round;
    }
}
