package nl.kmc.core;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which games are running right now and who is in them, so the server-wide protections (no PvP, lobby
 * protection) can step aside for a match instead of fighting it:
 *
 * <ul>
 *   <li>{@code GlobalPvPListener} cancels all player-vs-player damage — but a game that handles PvP itself
 *       (SurvivalGames, SkyWars, TNT Tag, ...) registers here and its players are exempt.</li>
 *   <li>{@code LobbyProtectionListener} blocks damage/building near the lobby — it must not apply while a
 *       game runs (a test arena or small server easily lies within the lobby radius).</li>
 * </ul>
 *
 * Opened/closed by {@code BaseGameManager}; read on the main thread, but concurrent-safe anyway.
 */
public final class RunningGames {

    private record Entry(boolean handlesPvp, Supplier<Collection<UUID>> participants) {}

    private static final Map<String, Entry> RUNNING = new ConcurrentHashMap<>();

    private static final java.util.Set<String> HIDE_NAMETAGS = ConcurrentHashMap.newKeySet();

    private RunningGames() {}

    /** A running game asks for player nametags to be hidden (e.g. QuakeCraft). */
    public static void hideNametags(String gameId, boolean hide) {
        if (hide) HIDE_NAMETAGS.add(gameId); else HIDE_NAMETAGS.remove(gameId);
    }

    /** True while at least one running game wants nametags hidden. */
    public static boolean nametagsHidden() { return !HIDE_NAMETAGS.isEmpty(); }

    public static void open(String gameId, boolean handlesPlayerPvp, Supplier<Collection<UUID>> participants) {
        RUNNING.put(gameId, new Entry(handlesPlayerPvp, participants));
    }

    public static void close(String gameId) { RUNNING.remove(gameId); HIDE_NAMETAGS.remove(gameId); }

    /** True while at least one game is in its running phases. */
    public static boolean anyRunning() { return !RUNNING.isEmpty(); }

    /** True if {@code uuid} takes part in a running game. */
    public static boolean isParticipant(UUID uuid) {
        for (Entry e : RUNNING.values()) if (e.participants().get().contains(uuid)) return true;
        return false;
    }

    /**
     * True if a running game that handles player-vs-player damage itself has the attacker or the victim
     * among its participants — the global "no PvP" rule must then leave the event to that game.
     */
    public static boolean pvpHandledByGame(UUID attacker, UUID victim) {
        for (Entry e : RUNNING.values()) {
            if (!e.handlesPvp()) continue;
            Collection<UUID> p = e.participants().get();
            if (p.contains(attacker) || p.contains(victim)) return true;
        }
        return false;
    }
}
