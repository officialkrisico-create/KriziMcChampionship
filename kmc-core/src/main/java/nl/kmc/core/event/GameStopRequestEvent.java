package nl.kmc.core.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when an admin (or the automation) force-ends a game — {@code /kmcauto endgame}, skipping a game —
 * so the running game itself actually stops. Without this the rotation moved on while the game kept
 * running in the background and later pulled players out of the next game.
 *
 * <p>Every {@code BaseGameManager} listens for it while it is running, so game plugins need no extra wiring.
 */
public final class GameStopRequestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String gameId;

    public GameStopRequestEvent(String gameId) { this.gameId = gameId; }

    public String getGameId() { return gameId; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
