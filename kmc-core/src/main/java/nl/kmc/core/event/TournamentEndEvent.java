package nl.kmc.core.event;

import nl.kmc.core.domain.KMCTeam;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class TournamentEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int     eventNumber;
    private final KMCTeam winningTeam;

    public TournamentEndEvent(int eventNumber, KMCTeam winningTeam) {
        this.eventNumber = eventNumber;
        this.winningTeam = winningTeam;
    }

    public int     getEventNumber() { return eventNumber; }
    public KMCTeam getWinningTeam() { return winningTeam; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
