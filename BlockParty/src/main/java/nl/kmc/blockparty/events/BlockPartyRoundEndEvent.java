package nl.kmc.blockparty.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Fired once per round, right after the wrong-colour blocks are cleared and eliminations resolved. */
public class BlockPartyRoundEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int round;
    private final List<UUID> survivors;
    private final List<UUID> eliminated;

    public BlockPartyRoundEndEvent(int round, List<UUID> survivors, List<UUID> eliminated) {
        this.round = round;
        this.survivors = List.copyOf(survivors);
        this.eliminated = List.copyOf(eliminated);
    }

    public int getRound() { return round; }
    public List<UUID> getSurvivors()  { return Collections.unmodifiableList(survivors); }
    public List<UUID> getEliminated() { return Collections.unmodifiableList(eliminated); }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
