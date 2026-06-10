package nl.kmc.tnttag.models;

import java.util.UUID;

/**
 * Per-player TNT Tag state — alive/out, "it" status, and the full set of
 * tournament statistics the V3 game tracks.
 */
public class PlayerState {

    private final UUID   uuid;
    private final String name;

    private boolean alive = true;
    private boolean isIt;                 // currently holds the bomb
    private boolean shielded;             // shield powerup blocks one transfer
    private boolean everReceivedTnt;      // for the "Untouchable" clutch/achievement
    private boolean reachedFinalFive;

    private int  tagsLanded;              // bombs passed ON to others
    private int  tntReceives;             // bombs received
    private int  clutchTransfers;         // last-second passes
    private int  powerupsCollected;
    private int  roundsSurvived;
    private int  totalPoints;
    private int  eliminatedAtRound = -1;

    private long lastTagMs;               // pass cooldown
    private long survivalStartMs;         // when the match started for this player
    private long survivalMs;              // frozen at elimination

    public PlayerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID    getUuid()              { return uuid; }
    public String  getName()              { return name; }
    public boolean isAlive()              { return alive; }
    public boolean isIt()                 { return isIt; }
    public boolean isShielded()           { return shielded; }
    public boolean hasEverReceivedTnt()   { return everReceivedTnt; }
    public boolean hasReachedFinalFive()  { return reachedFinalFive; }
    public int     getTagsLanded()        { return tagsLanded; }
    public int     getTntReceives()       { return tntReceives; }
    public int     getClutchTransfers()   { return clutchTransfers; }
    public int     getPowerupsCollected() { return powerupsCollected; }
    public int     getRoundsSurvived()    { return roundsSurvived; }
    public int     getTotalPoints()       { return totalPoints; }
    public int     getEliminatedAtRound() { return eliminatedAtRound; }
    public long    getLastTagMs()         { return lastTagMs; }
    public long    getSurvivalMs()        { return survivalMs; }

    public void setIt(boolean it)              { this.isIt = it; }
    public void setShielded(boolean s)         { this.shielded = s; }
    public void markFinalFive()                { this.reachedFinalFive = true; }
    public void markReceivedTnt()              { this.everReceivedTnt = true; this.tntReceives++; }
    public void incrementTags()                { this.tagsLanded++; }
    public void incrementClutch()              { this.clutchTransfers++; }
    public void incrementPowerups()            { this.powerupsCollected++; }
    public void incrementRoundsSurvived()      { this.roundsSurvived++; }
    public void addPoints(int amount)          { this.totalPoints += amount; }
    public void recordTagPass()                { this.lastTagMs = System.currentTimeMillis(); }
    public void startSurvivalTimer()           { this.survivalStartMs = System.currentTimeMillis(); }
    public long liveSurvivalMs()               { return alive ? System.currentTimeMillis() - survivalStartMs : survivalMs; }

    public void eliminate(int round) {
        this.alive = false;
        this.isIt = false;
        this.shielded = false;
        this.eliminatedAtRound = round;
        this.survivalMs = System.currentTimeMillis() - survivalStartMs;
    }
}
