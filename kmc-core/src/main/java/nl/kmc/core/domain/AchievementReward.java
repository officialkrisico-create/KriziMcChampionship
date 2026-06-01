package nl.kmc.core.domain;

/**
 * Non-gameplay reward granted when an achievement unlocks.
 * Only cosmetic/prestige rewards are allowed — no stat boosts or gameplay advantages.
 */
public final class AchievementReward {

    public enum Type {
        NONE,
        BADGE,          // cosmetic badge shown in /kmcstats profile
        TITLE,          // prefix title shown in chat / GUI
        HOF_ENTRY,      // Hall of Fame nomination
        PROFILE_STAT    // highlighted stat on the profile page
    }

    private final Type   type;
    private final String value;  // badge id, title string, stat key, etc.

    public AchievementReward(Type type, String value) {
        this.type  = type;
        this.value = value;
    }

    public static AchievementReward none() { return new AchievementReward(Type.NONE, ""); }

    public Type   getType()  { return type; }
    public String getValue() { return value; }

    @Override public String toString() { return type + ":" + value; }
}
