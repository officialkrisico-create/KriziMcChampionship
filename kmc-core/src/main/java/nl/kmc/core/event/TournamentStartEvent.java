package nl.kmc.core.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class TournamentStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int eventNumber;
    private final String tournamentName;

    public TournamentStartEvent(int eventNumber, String tournamentName) {
        this.eventNumber    = eventNumber;
        this.tournamentName = tournamentName;
    }

    public int    getEventNumber()    { return eventNumber; }
    public String getTournamentName() { return tournamentName; }

    @Override public HandlerList getHandlers()             { return HANDLERS; }
    public static HandlerList    getHandlerList()          { return HANDLERS; }
}
