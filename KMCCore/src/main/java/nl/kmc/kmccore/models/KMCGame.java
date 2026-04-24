package nl.kmc.kmccore.models;

import org.bukkit.Material;

/**
 * Represents a playable mini-game in the KMC tournament.
 *
 * <p>A game entry is essentially config data loaded at startup –
 * it stores the game's identity and display preferences.
 * Runtime state (active game, current winner, etc.) lives in
 * {@link nl.kmc.kmccore.managers.GameManager}.
 */
public class KMCGame {

    /** Unique identifier matching the config key (e.g. {@code team_skywars}). */
    private final String id;

    /** Human-readable display name shown to players. */
    private String displayName;

    /** Icon material used in vote GUIs. */
    private Material icon;

    /** Minimum number of players required to run this game. */
    private int minPlayers;

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public KMCGame(String id, String displayName, Material icon, int minPlayers) {
        this.id          = id;
        this.displayName = displayName;
        this.icon        = icon;
        this.minPlayers  = minPlayers;
    }

    // ----------------------------------------------------------------
    // Getters / setters
    // ----------------------------------------------------------------

    public String   getId()           { return id; }
    public String   getDisplayName()  { return displayName; }
    public Material getIcon()         { return icon; }
    public int      getMinPlayers()   { return minPlayers; }

    public void setDisplayName(String n)  { this.displayName = n; }
    public void setIcon(Material m)       { this.icon = m; }
    public void setMinPlayers(int v)      { this.minPlayers = Math.max(0, v); }

    @Override
    public String toString() {
        return "KMCGame{id=" + id + ", name=" + displayName + "}";
    }
}
