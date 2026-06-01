package nl.kmc.core.event;

import nl.kmc.core.domain.KMCPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired alongside Bukkit's PlayerDeathEvent for players active in a KMC game.
 * Carries game context (gameId, cause) so achievement checks don't need to
 * inspect raw Bukkit damage causes.
 *
 * <p>Game managers fire this from their death/elimination handlers:
 * <pre>{@code
 * new PlayerDeathContextEvent(player, kmcPlayer, gameId, DeathCause.VOID).callEvent();
 * }</pre>
 */
public final class PlayerDeathContextEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum DeathCause {
        PVP,         // killed by another player
        VOID,        // fell out of the world / below void Y
        LAVA,        // lava or fire damage
        MOB,         // environmental mob kill
        ENVIRONMENT, // fall, drowning, explosion, etc.
        UNKNOWN
    }

    private final Player    player;
    private final KMCPlayer kmcPlayer;
    private final String    gameId;
    private final DeathCause cause;

    public PlayerDeathContextEvent(Player player, KMCPlayer kmcPlayer,
                                   String gameId, DeathCause cause) {
        this.player    = player;
        this.kmcPlayer = kmcPlayer;
        this.gameId    = gameId;
        this.cause     = cause;
    }

    public Player     getPlayer()    { return player; }
    public KMCPlayer  getKmcPlayer() { return kmcPlayer; }
    public String     getGameId()    { return gameId; }
    public DeathCause getCause()     { return cause; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
