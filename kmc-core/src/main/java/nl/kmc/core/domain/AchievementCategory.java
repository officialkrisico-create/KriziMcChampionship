package nl.kmc.core.domain;

/** Logical grouping shown in the achievement GUI and used by admin filters. */
public enum AchievementCategory {
    CHAMPIONSHIP ("Championship",  "§6"),
    PVP          ("PvP",           "§c"),
    MOVEMENT     ("Movement",      "§a"),
    TEAM         ("Team",          "§9"),
    PROGRESSION  ("Progression",   "§e"),
    CHAOS        ("Chaos",         "§d"),
    SECRET       ("Secret",        "§5"),
    LEGENDARY    ("Legendary",     "§6§l");

    private final String displayName;
    private final String colorCode;

    AchievementCategory(String displayName, String colorCode) {
        this.displayName = displayName;
        this.colorCode   = colorCode;
    }

    public String getDisplayName() { return displayName; }
    public String getColorCode()   { return colorCode; }
}
