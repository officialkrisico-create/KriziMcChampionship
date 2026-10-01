package nl.kmc.blockparty.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Fired once the match is fully over — {@code placement} is finish order, winner first. */
public class BlockPartyGameEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID winner; // may be null if nobody finished
    private final List<UUID> placement;

    public BlockPartyGameEndEvent(UUID winner, List<UUID> placement) {
        this.winner = winner;
        this.placement = List.copyOf(placement);
    }

    public UUID getWinner() { return winner; }
    public List<UUID> getPlacement() { return Collections.unmodifiableList(placement); }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
