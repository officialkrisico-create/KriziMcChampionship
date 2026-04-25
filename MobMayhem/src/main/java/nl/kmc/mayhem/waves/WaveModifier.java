package nl.kmc.mayhem.waves;

/**
 * Random modifiers that apply to all mobs in a wave.
 *
 * <p>Picked at the start of each wave (configurable chance). Multiple
 * can be active at once. Adds variety without bloating the wave
 * definitions themselves.
 *
 * <p>From the spec:
 *   - DOUBLE_MOBS: 2× the configured spawn count
 *   - SPEED_MOBS:  mobs get Speed II
 *   - LOW_VISIBILITY: blindness applied to players (fog effect)
 *   - EXPLOSIVE_MOBS: mobs explode on death
 */
public enum WaveModifier {
    NONE          ("Normaal",        ""),
    DOUBLE_MOBS   ("Dubbel Mobs",    "&c×2 mobs"),
    SPEED_MOBS    ("Snelle Mobs",    "&aSpeed II"),
    LOW_VISIBILITY("Lage Zicht",     "&8Fog"),
    EXPLOSIVE_MOBS("Explosieve Mobs","&4Boom"),
    HEALTHY_MOBS  ("Sterke Mobs",    "&5×2 HP"),
    POISON_TOUCH  ("Gif Mobs",       "&2Poison");

    public final String displayName;
    public final String shortLabel;

    WaveModifier(String displayName, String shortLabel) {
        this.displayName = displayName;
        this.shortLabel  = shortLabel;
    }
}
