package nl.kmc.blockparty.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Fired once, right as a Block Party match actually begins (after the grace period). */
public class BlockPartyGameStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<UUID> participants;

    public BlockPartyGameStartEvent(List<UUID> participants) {
        this.participants = List.copyOf(participants);
    }

    public List<UUID> getParticipants() { return Collections.unmodifiableList(participants); }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
