package nl.kmc.core.event;

import nl.kmc.core.domain.KMCPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when a player is eliminated from the current game (not necessarily dead). */
public final class PlayerEliminatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player    bukkit;
    private final KMCPlayer kmc;
    private final String    gameId;
    private final int       placement;   // 1-based final rank, 0 if not yet determined
    private final int       remainingPlayers;

    public PlayerEliminatedEvent(Player bukkit, KMCPlayer kmc, String gameId,
                                 int placement, int remainingPlayers) {
        this.bukkit           = bukkit;
        this.kmc              = kmc;
        this.gameId           = gameId;
        this.placement        = placement;
        this.remainingPlayers = remainingPlayers;
    }

    public Player    getPlayer()           { return bukkit; }
    public KMCPlayer getKmcPlayer()        { return kmc; }
    public String    getGameId()           { return gameId; }
    public int       getPlacement()        { return placement; }
    public int       getRemainingPlayers() { return remainingPlayers; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
