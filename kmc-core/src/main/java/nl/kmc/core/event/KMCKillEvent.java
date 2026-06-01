package nl.kmc.core.event;

import nl.kmc.core.domain.KMCPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when one KMC participant kills another during a game.
 * Cancelling this event suppresses the default kill-point award.
 */
public final class KMCKillEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player    killerBukkit;
    private final Player    victimBukkit;
    private final KMCPlayer killer;
    private final KMCPlayer victim;
    private final String    gameId;
    private boolean         cancelled;

    public KMCKillEvent(Player killerBukkit, Player victimBukkit,
                        KMCPlayer killer, KMCPlayer victim, String gameId) {
        this.killerBukkit = killerBukkit;
        this.victimBukkit = victimBukkit;
        this.killer       = killer;
        this.victim       = victim;
        this.gameId       = gameId;
    }

    public Player    getKillerPlayer() { return killerBukkit; }
    public Player    getVictimPlayer() { return victimBukkit; }
    public KMCPlayer getKiller()       { return killer; }
    public KMCPlayer getVictim()       { return victim; }
    public String    getGameId()       { return gameId; }

    @Override public boolean isCancelled()         { return cancelled; }
    @Override public void    setCancelled(boolean c) { this.cancelled = c; }
    @Override public HandlerList getHandlers()     { return HANDLERS; }
    public static HandlerList getHandlerList()     { return HANDLERS; }
}
