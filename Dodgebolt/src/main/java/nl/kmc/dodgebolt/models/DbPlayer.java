package nl.kmc.dodgebolt.models;

import nl.kmc.dodgebolt.managers.ArenaConfig.Side;

import java.util.UUID;

/** One participant of the match: which side, whether they are still in this round, and their kills. */
public final class DbPlayer {

    private final UUID uuid;
    private final String name;
    private final String teamId;
    private final Side side;

    private boolean alive = true;
    private int roundKills;
    private int matchKills;

    public DbPlayer(UUID uuid, String name, String teamId, Side side) {
        this.uuid = uuid;
        this.name = name;
        this.teamId = teamId;
        this.side = side;
    }

    public UUID   getUuid()   { return uuid; }
    public String getName()   { return name; }
    public String getTeamId() { return teamId; }
    public Side   getSide()   { return side; }

    public boolean isAlive()          { return alive; }
    public void    setAlive(boolean a) { this.alive = a; }

    public int  getRoundKills() { return roundKills; }
    public int  getMatchKills() { return matchKills; }

    public void addKill() { roundKills++; matchKills++; }

    /** New round: back in, round kills reset (match kills stay). */
    public void resetForRound() { alive = true; roundKills = 0; }
}
