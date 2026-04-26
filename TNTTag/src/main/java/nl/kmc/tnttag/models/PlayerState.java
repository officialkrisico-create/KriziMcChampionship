package nl.kmc.tnttag.models;

import java.util.UUID;

/**
 * Per-player TNT Tag state.
 *
 * <p>Tracks alive/eliminated, current "it" status, points earned,
 * tags landed, elimination round (for ranking).
 */
public class PlayerState {

    private final UUID    uuid;
    private final String  name;

    private boolean alive = true;
    private boolean isIt;            // currently has the bomb
    private int     tagsLanded;      // # of times this player passed the bomb
    private int     roundsSurvived;
    private int     totalPoints;
    private int     eliminatedAtRound = -1;
    private long    lastTagMs;       // for cooldown — can't pass back instantly

    public PlayerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID    getUuid()             { return uuid; }
    public String  getName()              { return name; }
    public boolean isAlive()              { return alive; }
    public boolean isIt()                 { return isIt; }
    public int     getTagsLanded()        { return tagsLanded; }
    public int     getRoundsSurvived()    { return roundsSurvived; }
    public int     getTotalPoints()       { return totalPoints; }
    public int     getEliminatedAtRound() { return eliminatedAtRound; }
    public long    getLastTagMs()         { return lastTagMs; }

    public void    setIt(boolean isIt)    { this.isIt = isIt; }
    public void    incrementTags()         { this.tagsLanded++; }
    public void    incrementRoundsSurvived() { this.roundsSurvived++; }
    public void    addPoints(int amount)   { this.totalPoints += amount; }
    public void    recordTagPass()         { this.lastTagMs = System.currentTimeMillis(); }

    public void eliminate(int round) {
        this.alive = false;
        this.isIt = false;
        this.eliminatedAtRound = round;
    }
}
