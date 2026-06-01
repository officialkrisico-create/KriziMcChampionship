package nl.kmc.core.event;

import nl.kmc.core.domain.GameRegistration;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class GameStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final GameRegistration game;
    private final int round;

    public GameStartEvent(GameRegistration game, int round) {
        this.game  = game;
        this.round = round;
    }

    public GameRegistration getGame()  { return game; }
    public int              getRound() { return round; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
