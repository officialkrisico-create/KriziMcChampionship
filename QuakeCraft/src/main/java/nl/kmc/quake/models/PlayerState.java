package nl.kmc.quake.models;

import java.util.UUID;

/**
 * Per-player state during an active QuakeCraft game.
 *
 * <p>Tracks kills, deaths, killstreaks, and the active powerup
 * (if any). Cleared at game end.
 */
public class PlayerState {

    private final UUID   uuid;
    private final String name;

    private int kills;
    private int deaths;
    private int currentStreak;
    private int bestStreak;
    private int placement = -1;

    /** Last time the railgun was fired (millis), for cooldown enforcement. */
    private long lastShotMs;

    /** Currently equipped powerup (null = base railgun only). */
    private ActivePowerup activePowerup;

    public PlayerState(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    // ---- Stats -----------------------------------------------------

    public void addKill() {
        kills++;
        currentStreak++;
        if (currentStreak > bestStreak) bestStreak = currentStreak;
    }

    public void addDeath() {
        deaths++;
        currentStreak = 0;
    }

    // ---- Powerup ---------------------------------------------------

    public boolean hasPowerup()                    { return activePowerup != null; }
    public ActivePowerup getActivePowerup()        { return activePowerup; }
    public void setActivePowerup(ActivePowerup p)  { this.activePowerup = p; }
    public void clearPowerup()                     { this.activePowerup = null; }

    /**
     * Decrements remaining uses on the powerup. If it hits 0, the
     * powerup is removed.
     *
     * @return true if the powerup is still active, false if exhausted
     */
    public boolean consumePowerupUse() {
        if (activePowerup == null) return false;
        activePowerup.decrementUses();
        if (activePowerup.getRemainingUses() <= 0) {
            activePowerup = null;
            return false;
        }
        return true;
    }

    // ---- Cooldown --------------------------------------------------

    public boolean canShoot(long cooldownMs) {
        return System.currentTimeMillis() - lastShotMs >= cooldownMs;
    }

    public void markShot() { this.lastShotMs = System.currentTimeMillis(); }

    public long msUntilNextShot(long cooldownMs) {
        long elapsed = System.currentTimeMillis() - lastShotMs;
        return Math.max(0, cooldownMs - elapsed);
    }

    // ---- Getters ---------------------------------------------------

    public UUID   getUuid()           { return uuid; }
    public String getName()           { return name; }
    public int    getKills()          { return kills; }
    public int    getDeaths()         { return deaths; }
    public int    getCurrentStreak()  { return currentStreak; }
    public int    getBestStreak()     { return bestStreak; }
    public int    getPlacement()      { return placement; }
    public void   setPlacement(int p) { this.placement = p; }

    public double getKD() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }
}
