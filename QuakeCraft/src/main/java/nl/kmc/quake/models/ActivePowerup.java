package nl.kmc.quake.models;

/**
 * A powerup currently held by a player. Tracks remaining uses
 * (for weapons) or expiry time (for buffs like Speed II).
 */
public class ActivePowerup {

    private final PowerupType type;
    private int remainingUses;
    private final long expiresAtMs; // for time-based buffs; 0 = use-based

    public ActivePowerup(PowerupType type, int uses) {
        this.type           = type;
        this.remainingUses  = uses;
        this.expiresAtMs    = 0;
    }

    public ActivePowerup(PowerupType type, long durationMs) {
        this.type           = type;
        this.remainingUses  = Integer.MAX_VALUE;
        this.expiresAtMs    = System.currentTimeMillis() + durationMs;
    }

    public PowerupType getType()          { return type; }
    public int         getRemainingUses() { return remainingUses; }
    public void        decrementUses()    { remainingUses--; }

    public boolean isExpired() {
        if (expiresAtMs == 0) return false;
        return System.currentTimeMillis() >= expiresAtMs;
    }

    public long msRemaining() {
        if (expiresAtMs == 0) return -1;
        return Math.max(0, expiresAtMs - System.currentTimeMillis());
    }
}
