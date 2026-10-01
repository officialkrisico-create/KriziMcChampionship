package nl.kmc.blockparty.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired for each individual player who survives a round (standing on a surviving colour). */
public class BlockPartyPlayerSurviveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final int  round;
    private final boolean clutch; // stepped onto the right colour in the final second

    public BlockPartyPlayerSurviveEvent(UUID playerId, int round, boolean clutch) {
        this.playerId = playerId;
        this.round = round;
        this.clutch = clutch;
    }

    public UUID getPlayerId() { return playerId; }
    public int  getRound() { return round; }
    public boolean isClutch() { return clutch; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
