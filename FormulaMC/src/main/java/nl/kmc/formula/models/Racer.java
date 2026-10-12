package nl.kmc.formula.models;

import org.bukkit.Location;

import java.util.UUID;

/** Per-race state of one participant. */
public final class Racer {

    private final UUID uuid;
    private final String name;
    private final String teamId;

    private UUID boatId;
    /** Checkpoints passed in the current lap (0..checkpoints.size()). */
    private int nextCheckpoint;
    private int lapsCompleted;
    private long lapStartMs;
    private long lastLapMs = -1;
    private long bestLapMs = -1;
    private boolean finished;
    private long finishMs;
    private int finishPlace;

    private Location lastPoll;
    private long lastMovedMs;
    private Location lastSafe;

    public Racer(UUID uuid, String name, String teamId) {
        this.uuid = uuid;
        this.name = name;
        this.teamId = teamId;
    }

    public UUID getUuid()        { return uuid; }
    public String getName()      { return name; }
    public String getTeamId()    { return teamId; }

    public UUID getBoatId()      { return boatId; }
    public void setBoatId(UUID id) { this.boatId = id; }

    public int  getNextCheckpoint()        { return nextCheckpoint; }
    public void setNextCheckpoint(int i)   { this.nextCheckpoint = i; }
    public int  getLapsCompleted()         { return lapsCompleted; }
    public long getLapStartMs()            { return lapStartMs; }
    public void setLapStartMs(long ms)     { this.lapStartMs = ms; }
    public long getLastLapMs()             { return lastLapMs; }
    public long getBestLapMs()             { return bestLapMs; }

    /** Records a finished lap; returns the lap time in ms. */
    public long completeLap(long now) {
        long lap = now - lapStartMs;
        lapsCompleted++;
        lastLapMs = lap;
        if (bestLapMs < 0 || lap < bestLapMs) bestLapMs = lap;
        lapStartMs = now;
        nextCheckpoint = 0;
        return lap;
    }

    public boolean isFinished()   { return finished; }
    public long getFinishMs()     { return finishMs; }
    public int  getFinishPlace()  { return finishPlace; }
    public void markFinished(long totalMs, int place) {
        this.finished = true;
        this.finishMs = totalMs;
        this.finishPlace = place;
    }

    public Location getLastPoll()        { return lastPoll; }
    public void setLastPoll(Location l)  { this.lastPoll = l; }
    public long getLastMovedMs()         { return lastMovedMs; }
    public void setLastMovedMs(long ms)  { this.lastMovedMs = ms; }
    public Location getLastSafe()        { return lastSafe; }
    public void setLastSafe(Location l)  { this.lastSafe = l; }
}
