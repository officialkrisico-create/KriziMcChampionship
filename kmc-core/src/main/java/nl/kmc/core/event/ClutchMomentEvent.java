package nl.kmc.core.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when the clutch-detection engine identifies a significant moment. */
public final class ClutchMomentEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum ClutchType {
        OUTNUMBERED_VICTORY,
        LAST_SURVIVOR,
        COMEBACK_WIN,
        KILL_STREAK,
        LAST_SECOND_TAG,
        LARGE_SCORING_SWING,
        PERFECT_GAME
    }

    private final Player     player;
    private final ClutchType type;
    private final String     description;
    private final String     gameId;

    public ClutchMomentEvent(Player player, ClutchType type, String description, String gameId) {
        this.player      = player;
        this.type        = type;
        this.description = description;
        this.gameId      = gameId;
    }

    public Player     getPlayer()      { return player; }
    public ClutchType getType()        { return type; }
    public String     getDescription() { return description; }
    public String     getGameId()      { return gameId; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
