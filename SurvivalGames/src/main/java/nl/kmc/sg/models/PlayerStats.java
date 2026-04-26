package nl.kmc.sg.models;

import java.util.UUID;

/**
 * Per-player Survival Games stats.
 */
public class PlayerStats {

    private final UUID    uuid;
    private final String  name;

    private boolean alive = true;
    private int     kills;
    private int     eliminationOrder = -1;
    private long    eliminatedAtMs;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID    getUuid()              { return uuid; }
    public String  getName()               { return name; }
    public boolean isAlive()               { return alive; }
    public int     getKills()              { return kills; }
    public int     getEliminationOrder()   { return eliminationOrder; }
    public long    getEliminatedAtMs()     { return eliminatedAtMs; }

    public void incrementKills() { kills++; }

    public void eliminate(int order) {
        if (!alive) return;
        this.alive = false;
        this.eliminationOrder = order;
        this.eliminatedAtMs = System.currentTimeMillis();
    }
}
