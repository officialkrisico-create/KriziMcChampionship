package nl.kmc.game.api;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.stats.model.GameStats;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;
import java.util.UUID;

/**
 * Fired by a game plugin when the game has fully finished.
 * The tournament engine listens for this to advance the tournament flow.
 */
public final class GameResultEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final GameRegistration     registration;
    private final String               winnerDescription;
    private final UUID                 mvpUuid;
    private final String               mvpName;
    private final List<GameStats>      finalStats;
    private final List<UUID>           participantOrder; // placement 1..N

    public GameResultEvent(GameRegistration registration,
                           String winnerDescription,
                           UUID mvpUuid,
                           String mvpName,
                           List<GameStats> finalStats,
                           List<UUID> participantOrder) {
        this.registration     = registration;
        this.winnerDescription = winnerDescription;
        this.mvpUuid          = mvpUuid;
        this.mvpName          = mvpName;
        this.finalStats       = List.copyOf(finalStats);
        this.participantOrder = List.copyOf(participantOrder);
    }

    public GameRegistration  getRegistration()      { return registration; }
    public String            getWinnerDescription() { return winnerDescription; }
    public UUID              getMvpUuid()           { return mvpUuid; }
    public String            getMvpName()           { return mvpName; }
    public List<GameStats>   getFinalStats()        { return finalStats; }
    public List<UUID>        getParticipantOrder()  { return participantOrder; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
