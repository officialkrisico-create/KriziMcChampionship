package nl.kmc.parkour.models;

import org.bukkit.ChatColor;

/**
 * Difficulty tier for a checkpoint within a parkour stage.
 *
 * <p>A "stage" is a stretch of the course between two narrowing points.
 * Stages may have multiple alternate checkpoints — one per difficulty —
 * and the player picks which path to attempt. Reaching any one of them
 * counts as completing that stage and advances them.
 *
 * <p>Points are awarded as {@code basePoints * multiplier}.
 */
public enum Difficulty {

    /** Default for non-branching checkpoints (start, finish, required mid-points). */
    MAIN  (1.0,  ChatColor.WHITE,  "Main"),
    EASY  (1.0,  ChatColor.GREEN,  "Easy"),
    MEDIUM(1.5,  ChatColor.YELLOW, "Medium"),
    HARD  (2.0,  ChatColor.RED,    "Hard");

    private final double multiplier;
    private final ChatColor chatColor;
    private final String label;

    Difficulty(double multiplier, ChatColor chatColor, String label) {
        this.multiplier = multiplier;
        this.chatColor  = chatColor;
        this.label      = label;
    }

    public double    getMultiplier() { return multiplier; }
    public ChatColor getChatColor()  { return chatColor; }
    public String    getLabel()      { return label; }

    /** Returns "Easy", "Medium", "Hard", or "Main" formatted with color. */
    public String formatted() {
        return chatColor + label;
    }

    /** Lenient parsing — accepts "easy", "MEDIUM", "Hard", etc. */
    public static Difficulty parse(String raw) {
        if (raw == null) return MAIN;
        try { return Difficulty.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return MAIN; }
    }
}
