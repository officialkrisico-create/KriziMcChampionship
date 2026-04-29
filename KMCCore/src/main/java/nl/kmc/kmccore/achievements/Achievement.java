package nl.kmc.kmccore.achievements;

import org.bukkit.ChatColor;
import org.bukkit.Material;

/**
 * A single achievement definition. Immutable — all instances live in
 * the {@link AchievementRegistry}.
 *
 * <p>Each achievement has:
 * <ul>
 *   <li>A unique {@code id} (snake_case, used in DB and config)</li>
 *   <li>Display {@code name} and {@code description}</li>
 *   <li>A {@link Rarity} tier: COMMON, RARE, or LEGENDARY</li>
 *   <li>An icon ({@link Material}) for the GUI</li>
 *   <li>An optional progress threshold for "do X N times" achievements</li>
 * </ul>
 *
 * <p>The actual evaluation happens in {@link AchievementManager} — this
 * class just describes what the achievement IS, not how to check it.
 */
public class Achievement {

    public enum Rarity {
        COMMON   (ChatColor.GRAY,    "Common",     0xAAAAAA),
        RARE     (ChatColor.AQUA,    "Rare",       0x55FFFF),
        LEGENDARY(ChatColor.GOLD,    "Legendary",  0xFFAA00);

        private final ChatColor chatColor;
        private final String    label;
        private final int       rgb;

        Rarity(ChatColor chatColor, String label, int rgb) {
            this.chatColor = chatColor;
            this.label     = label;
            this.rgb       = rgb;
        }
        public ChatColor getChatColor() { return chatColor; }
        public String    getLabel()     { return label; }
        public int       getRgb()       { return rgb; }

        /** True if earning this rarity should broadcast to the whole server. */
        public boolean broadcastOnUnlock() {
            return this != COMMON;
        }
    }

    private final String   id;
    private final String   name;
    private final String   description;
    private final Rarity   rarity;
    private final Material icon;
    private final int      progressThreshold;  // 0 = boolean (one-shot), >0 = counter target

    public Achievement(String id, String name, String description,
                       Rarity rarity, Material icon, int progressThreshold) {
        this.id                = id;
        this.name              = name;
        this.description       = description;
        this.rarity            = rarity;
        this.icon              = icon;
        this.progressThreshold = progressThreshold;
    }

    public String   getId()                { return id; }
    public String   getName()               { return name; }
    public String   getDescription()        { return description; }
    public Rarity   getRarity()             { return rarity; }
    public Material getIcon()               { return icon; }
    public int      getProgressThreshold()  { return progressThreshold; }
    public boolean  isProgressBased()       { return progressThreshold > 0; }
}
