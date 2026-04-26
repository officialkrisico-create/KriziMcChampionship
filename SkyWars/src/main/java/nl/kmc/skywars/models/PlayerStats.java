package nl.kmc.skywars.models;

import java.util.UUID;

/**
 * Per-player SkyWars stats.
 */
public class PlayerStats {

    private final UUID    uuid;
    private final String  name;
    private final String  teamId;     // which SW team they're on (matches Team.id)

    private boolean alive = true;
    private int     kills;
    private int     eliminationOrder = -1;  // 0 = first to die

    public PlayerStats(UUID uuid, String name, String teamId) {
        this.uuid   = uuid;
        this.name   = name;
        this.teamId = teamId;
    }

    public UUID    getUuid()              { return uuid; }
    public String  getName()               { return name; }
    public String  getTeamId()             { return teamId; }
    public boolean isAlive()               { return alive; }
    public int     getKills()              { return kills; }
    public int     getEliminationOrder()   { return eliminationOrder; }

    public void incrementKills() { kills++; }

    public void eliminate(int order) {
        if (!alive) return;
        this.alive = false;
        this.eliminationOrder = order;
    }
}
