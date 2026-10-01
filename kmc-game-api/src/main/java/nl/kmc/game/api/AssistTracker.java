package nl.kmc.game.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the last two distinct attackers per victim so a game can credit an
 * assist alongside the kill (80/20 split by default). Call {@link #recordHit}
 * on every damage event; at death, {@link #getKiller} is the usual "last hit"
 * credit and {@link #getAssist} is a distinct earlier attacker, but only if
 * their hit landed within the assist window — a hit from ten minutes ago
 * doesn't deserve a cut.
 */
public final class AssistTracker {

    private record Hit(UUID attacker, long atMs) {}

    private final Map<UUID, Hit> lastHit       = new HashMap<>();
    private final Map<UUID, Hit> secondLastHit = new HashMap<>();
    private final long windowMs;

    public AssistTracker(long windowMs) { this.windowMs = windowMs; }

    /** 10-second assist window. */
    public AssistTracker() { this(10_000L); }

    /** Records a hit from {@code attacker} on {@code victim}. Ignores self-damage. */
    public void recordHit(UUID victim, UUID attacker) {
        if (victim == null || attacker == null || victim.equals(attacker)) return;
        Hit prevLast = lastHit.get(victim);
        if (prevLast != null && !prevLast.attacker().equals(attacker)) {
            secondLastHit.put(victim, prevLast);
        }
        lastHit.put(victim, new Hit(attacker, System.currentTimeMillis()));
    }

    /**
     * The most recent attacker on this victim — the usual kill credit.
     * Null if nobody hit them, or the hit is older than the assist window
     * (e.g. a fall/void death long after the last PvP exchange).
     */
    public UUID getKiller(UUID victim) {
        Hit h = lastHit.get(victim);
        if (h == null || System.currentTimeMillis() - h.atMs() > windowMs) return null;
        return h.attacker();
    }

    /** A distinct second attacker within the assist window, or null if there isn't one. */
    public UUID getAssist(UUID victim) {
        Hit killerHit = lastHit.get(victim);
        Hit assistHit = secondLastHit.get(victim);
        if (killerHit == null || assistHit == null) return null;
        if (assistHit.attacker().equals(killerHit.attacker())) return null;
        if (killerHit.atMs() - assistHit.atMs() > windowMs) return null;
        return assistHit.attacker();
    }

    /** Clears tracked hits for a victim (call after crediting the kill). */
    public void clear(UUID victim) {
        lastHit.remove(victim);
        secondLastHit.remove(victim);
    }

    public void clearAll() {
        lastHit.clear();
        secondLastHit.clear();
    }

    /** {killerAmount, assistAmount} — assistAmount is 0 and the killer keeps everything if {@code assist} is null. */
    public record Split(int killerAmount, int assistAmount) {}

    /** Splits a kill reward between killer and assist using {@code assistFraction} (e.g. 0.2 = 80/20). */
    public static Split split(int totalPoints, UUID assist, double assistFraction) {
        if (assist == null) return new Split(totalPoints, 0);
        int assistAmount = (int) Math.round(totalPoints * assistFraction);
        return new Split(totalPoints - assistAmount, assistAmount);
    }
}
