package nl.kmc.core.event;

import nl.kmc.core.domain.GameRegistration;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class GameEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final GameRegistration game;
    private final UUID   mvpUuid;
    private final String mvpName;
    private final String winnerDescription; // e.g. team name or "Player X"
    private final int    round;

    public GameEndEvent(GameRegistration game, UUID mvpUuid, String mvpName,
                        String winnerDescription, int round) {
        this.game               = game;
        this.mvpUuid            = mvpUuid;
        this.mvpName            = mvpName;
        this.winnerDescription  = winnerDescription;
        this.round              = round;
    }

    public GameRegistration getGame()               { return game; }
    public UUID             getMvpUuid()            { return mvpUuid; }
    public String           getMvpName()            { return mvpName; }
    public String           getWinnerDescription()  { return winnerDescription; }
    public int              getRound()              { return round; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
