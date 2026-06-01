package nl.kmc.core.event;

import nl.kmc.core.domain.PointAward;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before points are applied. Handlers may modify {@link PointAward#setAmount(int)}
 * or cancel to suppress the award entirely.
 */
public final class PointsAwardedEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PointAward award;
    private boolean          cancelled;

    public PointsAwardedEvent(PointAward award) { this.award = award; }

    public PointAward getAward() { return award; }

    @Override public boolean isCancelled()           { return cancelled; }
    @Override public void    setCancelled(boolean c) { this.cancelled = c; }
    @Override public HandlerList getHandlers()       { return HANDLERS; }
    public static HandlerList    getHandlerList()    { return HANDLERS; }
}
