package nl.kmc.blockparty.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired for each individual player the moment they're eliminated (wrong colour or fell). */
public class BlockPartyPlayerEliminateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final int  round;
    private final int  roundsSurvived;

    public BlockPartyPlayerEliminateEvent(UUID playerId, int round, int roundsSurvived) {
        this.playerId = playerId;
        this.round = round;
        this.roundsSurvived = roundsSurvived;
    }

    public UUID getPlayerId() { return playerId; }
    public int  getRound() { return round; }
    public int  getRoundsSurvived() { return roundsSurvived; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
