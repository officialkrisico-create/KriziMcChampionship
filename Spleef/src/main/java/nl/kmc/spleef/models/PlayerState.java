package nl.kmc.spleef.models;

import java.util.UUID;

/**
 * Per-player Spleef state.
 *
 * <p>Tracks alive/eliminated, blocks broken (for stat awards), and
 * which layer they're currently on (multi-layer Spleef only).
 */
public class PlayerState {

    private final UUID    uuid;
    private final String  name;

    private boolean alive = true;
    private int     blocksBroken;
    private int     currentLayer;     // 0 = top layer
    private long    eliminatedAtMs;
    private int     eliminationOrder = -1;  // 0 = first to die, increases

    public PlayerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID    getUuid()               { return uuid; }
    public String  getName()                { return name; }
    public boolean isAlive()                { return alive; }
    public int     getBlocksBroken()        { return blocksBroken; }
    public int     getCurrentLayer()        { return currentLayer; }
    public long    getEliminatedAtMs()      { return eliminatedAtMs; }
    public int     getEliminationOrder()    { return eliminationOrder; }

    public void incrementBlocksBroken()     { this.blocksBroken++; }

    public void eliminate(int order) {
        if (!alive) return;
        this.alive = false;
        this.eliminatedAtMs = System.currentTimeMillis();
        this.eliminationOrder = order;
    }

    public void setCurrentLayer(int layer) { this.currentLayer = layer; }
}
