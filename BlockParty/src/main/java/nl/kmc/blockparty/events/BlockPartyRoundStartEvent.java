package nl.kmc.blockparty.events;

import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired at the start of each round, once the target colour has been picked. */
public class BlockPartyRoundStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int round;
    private final Material targetColour;
    private final Material displayedColour; // may differ from targetColour during a FAKE_COLOR chaos event
    private final long aliveCount;

    public BlockPartyRoundStartEvent(int round, Material targetColour, Material displayedColour, long aliveCount) {
        this.round = round;
        this.targetColour = targetColour;
        this.displayedColour = displayedColour;
        this.aliveCount = aliveCount;
    }

    public int getRound() { return round; }
    public Material getTargetColour() { return targetColour; }
    public Material getDisplayedColour() { return displayedColour; }
    public long getAliveCount() { return aliveCount; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
