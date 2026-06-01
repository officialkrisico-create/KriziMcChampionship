package nl.kmc.core.event;

import nl.kmc.core.domain.KMCTeam;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

public final class RoundEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int          roundNumber;
    private final List<KMCTeam> standings;

    public RoundEndEvent(int roundNumber, List<KMCTeam> standings) {
        this.roundNumber = roundNumber;
        this.standings   = List.copyOf(standings);
    }

    public int           getRoundNumber() { return roundNumber; }
    public List<KMCTeam> getStandings()   { return standings; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
