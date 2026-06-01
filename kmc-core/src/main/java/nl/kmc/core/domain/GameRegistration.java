package nl.kmc.core.domain;

import org.bukkit.Material;

/** Metadata a game plugin registers with the GameRegistry at startup. */
public final class GameRegistration {

    private final String id;
    private final String displayName;
    private final Material icon;
    private final int     minPlayers;
    private final int     maxPlayers;
    private final String  description;
    private final String  objective;

    private GameRegistration(Builder b) {
        this.id          = b.id;
        this.displayName = b.displayName;
        this.icon        = b.icon;
        this.minPlayers  = b.minPlayers;
        this.maxPlayers  = b.maxPlayers;
        this.description = b.description;
        this.objective   = b.objective;
    }

    public String   getId()          { return id; }
    public String   getDisplayName() { return displayName; }
    public Material getIcon()        { return icon; }
    public int      getMinPlayers()  { return minPlayers; }
    public int      getMaxPlayers()  { return maxPlayers; }
    public String   getDescription() { return description; }
    public String   getObjective()   { return objective; }

    public static Builder builder(String id, String displayName) {
        return new Builder(id, displayName);
    }

    public static final class Builder {
        private final String id;
        private final String displayName;
        private Material icon        = Material.GRASS_BLOCK;
        private int      minPlayers  = 2;
        private int      maxPlayers  = 100;
        private String   description = "";
        private String   objective   = "";

        private Builder(String id, String displayName) {
            this.id = id; this.displayName = displayName;
        }

        public Builder icon(Material icon)          { this.icon = icon;               return this; }
        public Builder minPlayers(int min)          { this.minPlayers = min;           return this; }
        public Builder maxPlayers(int max)          { this.maxPlayers = max;           return this; }
        public Builder description(String desc)     { this.description = desc;         return this; }
        public Builder objective(String objective)  { this.objective = objective;      return this; }
        public GameRegistration build()             { return new GameRegistration(this); }
    }

    @Override public String toString() { return "GameRegistration{id='" + id + "'}"; }
}
