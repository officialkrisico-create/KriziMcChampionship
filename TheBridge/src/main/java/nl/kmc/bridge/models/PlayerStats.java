package nl.kmc.bridge.models;

import java.util.UUID;

/**
 * Per-player Bridge stats.
 */
public class PlayerStats {

    private final UUID   uuid;
    private final String name;
    private final String teamId;

    private int kills;
    private int deaths;
    private int goals;       // goals this individual scored
    private int blocksPlaced;

    public PlayerStats(UUID uuid, String name, String teamId) {
        this.uuid   = uuid;
        this.name   = name;
        this.teamId = teamId;
    }

    public UUID    getUuid()          { return uuid; }
    public String  getName()           { return name; }
    public String  getTeamId()         { return teamId; }
    public int     getKills()          { return kills; }
    public int     getDeaths()         { return deaths; }
    public int     getGoals()          { return goals; }
    public int     getBlocksPlaced()   { return blocksPlaced; }

    public void    addKill()           { kills++; }
    public void    addDeath()          { deaths++; }
    public void    addGoal()           { goals++; }
    public void    addBlockPlaced()    { blocksPlaced++; }
}
