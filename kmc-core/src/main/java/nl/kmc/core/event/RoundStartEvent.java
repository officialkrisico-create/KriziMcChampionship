package nl.kmc.core.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class RoundStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int roundNumber;
    private final double multiplier;

    public RoundStartEvent(int roundNumber, double multiplier) {
        this.roundNumber = roundNumber;
        this.multiplier  = multiplier;
    }

    public int    getRoundNumber() { return roundNumber; }
    public double getMultiplier()  { return multiplier; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
