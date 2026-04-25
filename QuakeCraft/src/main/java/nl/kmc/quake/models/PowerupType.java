package nl.kmc.quake.models;

/**
 * Powerup definitions per your spec:
 *   Sniper [3 uses], Grenade [1 use], Shotgun [5 uses],
 *   Machine gun [25 uses], Speed II [15 sec]
 */
public enum PowerupType {
    SNIPER(true,  false),
    GRENADE(true, false),
    SHOTGUN(true, false),
    MACHINE_GUN(true, false),
    SPEED(false, true);

    private final boolean isWeapon;
    private final boolean isBuff;

    PowerupType(boolean isWeapon, boolean isBuff) {
        this.isWeapon = isWeapon;
        this.isBuff   = isBuff;
    }

    public boolean isWeapon() { return isWeapon; }
    public boolean isBuff()   { return isBuff; }

    public String getConfigKey() {
        return name().toLowerCase();
    }

    public static PowerupType fromConfigKey(String key) {
        if (key == null) return null;
        try { return valueOf(key.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
