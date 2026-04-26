package nl.kmc.skywars.models;

import org.bukkit.ChatColor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * SkyWars team — represents a team participating in this game.
 *
 * <p>Mapped from KMCCore teams when the game starts. Each team is
 * assigned an Island; their members spawn there.
 */
public class Team {

    private final String     id;            // matches KMCCore team id
    private final String     displayName;
    private final ChatColor  chatColor;
    private final Island     island;
    private final Set<UUID>  members = new HashSet<>();

    public Team(String id, String displayName, ChatColor chatColor, Island island) {
        this.id          = id;
        this.displayName = displayName;
        this.chatColor   = chatColor;
        this.island      = island;
    }

    public String     getId()          { return id; }
    public String     getDisplayName() { return displayName; }
    public ChatColor  getChatColor()   { return chatColor; }
    public Island     getIsland()      { return island; }
    public Set<UUID>  getMembers()     { return members; }
    public void       addMember(UUID u){ members.add(u); }
}
