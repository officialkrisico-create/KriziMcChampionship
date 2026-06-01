package nl.kmc.core.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired by game managers when a player completes a game-specific objective.
 * Achievement definitions with trigger GAME_OBJECTIVE subscribe to this event.
 *
 * <p>Game plugins fire this to report objectives without knowing about achievements:
 * <pre>{@code
 * new GameObjectiveEvent(player, "adventure_escape",
 *     GameObjectiveEvent.Type.RACE_FINISHED_FAST, elapsedMs).callEvent();
 * }</pre>
 */
public final class GameObjectiveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Type {
        /** Player scored a goal (The Bridge). */
        GOAL_SCORED,
        /** Player completed a lap (Adventure Escape, Elytra Endrium). */
        LAP_COMPLETED,
        /** Player finished a race. */
        RACE_FINISHED,
        /** Player finished a race under an achievement-defined time threshold. */
        RACE_FINISHED_FAST,
        /** Player completed an entire parkour run without dying. */
        PARKOUR_PERFECT_RUN,
        /** Player completed all checkpoints in an Elytra run without crashing. */
        ELYTRA_NO_CRASH,
        /** Bingo: player completed a line on the card. */
        BINGO_LINE,
        /** Mob Mayhem: player survived a full wave. */
        WAVE_SURVIVED,
        /** Generic checkpoint hit (Parkour Warrior, AE, Elytra). */
        CHECKPOINT_HIT,
        /** TGTTOS: player reached finish in 1st place. */
        TGTTOS_FIRST_FINISH
    }

    private final Player player;
    private final String gameId;
    private final Type   type;
    private final long   elapsedMs;  // duration of the activity (for time-based checks); 0 if N/A

    public GameObjectiveEvent(Player player, String gameId, Type type, long elapsedMs) {
        this.player    = player;
        this.gameId    = gameId;
        this.type      = type;
        this.elapsedMs = elapsedMs;
    }

    public GameObjectiveEvent(Player player, String gameId, Type type) {
        this(player, gameId, type, 0);
    }

    public Player getPlayer()    { return player; }
    public String getGameId()    { return gameId; }
    public Type   getType()      { return type; }
    public long   getElapsedMs() { return elapsedMs; }

    @Override public HandlerList getHandlers()    { return HANDLERS; }
    public static HandlerList    getHandlerList() { return HANDLERS; }
}
