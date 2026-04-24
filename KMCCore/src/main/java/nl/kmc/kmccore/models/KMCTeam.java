package nl.kmc.kmccore.models;

import org.bukkit.ChatColor;

import java.util.*;

/**
 * Represents one of the 8 KMC tournament teams.
 *
 * <p>Each team has:
 * <ul>
 *   <li>A unique ID (e.g. {@code rode_ratten})</li>
 *   <li>A display name in Dutch (e.g. "Rode Ratten")</li>
 *   <li>A Bukkit {@link ChatColor} used for nametags and scoreboard</li>
 *   <li>A list of member {@link UUID}s (max 4 by default)</li>
 *   <li>Cumulative tournament points and win count</li>
 * </ul>
 */
public class KMCTeam {

    /** Unique identifier (snake_case, matches config key). */
    private final String id;

    /** Human-readable Dutch name. */
    private String displayName;

    /** Primary chat colour. */
    private ChatColor color;

    /** Raw colour string for nametag teams (e.g. "RED"). */
    private String tagColor;

    /** UUIDs of current members. */
    private final List<UUID> members = new ArrayList<>();

    /** Cumulative points this tournament. */
    private int points;

    /** How many games this team has won. */
    private int wins;

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public KMCTeam(String id, String displayName, ChatColor color, String tagColor) {
        this.id          = id;
        this.displayName = displayName;
        this.color       = color;
        this.tagColor    = tagColor;
    }

    // ----------------------------------------------------------------
    // Member management
    // ----------------------------------------------------------------

    /** @return {@code true} if the player is a member of this team. */
    public boolean hasMember(UUID uuid) {
        return members.contains(uuid);
    }

    /**
     * Adds a player to this team.
     *
     * @param uuid player UUID
     * @return {@code false} if already a member
     */
    public boolean addMember(UUID uuid) {
        if (hasMember(uuid)) return false;
        members.add(uuid);
        return true;
    }

    /**
     * Removes a player from this team.
     *
     * @param uuid player UUID
     * @return {@code false} if not a member
     */
    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    /** @return unmodifiable view of the member list. */
    public List<UUID> getMembers() {
        return Collections.unmodifiableList(members);
    }

    /** @return number of current members. */
    public int getMemberCount() {
        return members.size();
    }

    // ----------------------------------------------------------------
    // Points / scoring
    // ----------------------------------------------------------------

    public int  getPoints()         { return points; }
    public void setPoints(int v)    { this.points = Math.max(0, v); }
    public void addPoints(int v)    { this.points = Math.max(0, this.points + v); }
    public void removePoints(int v) { this.points = Math.max(0, this.points - v); }

    public int  getWins()           { return wins; }
    public void setWins(int v)      { this.wins = Math.max(0, v); }
    public void addWin()            { this.wins++; }

    // ----------------------------------------------------------------
    // Getters / setters
    // ----------------------------------------------------------------

    public String    getId()          { return id; }
    public String    getDisplayName() { return displayName; }
    public ChatColor getColor()       { return color; }
    public String    getTagColor()    { return tagColor; }

    public void setDisplayName(String n) { this.displayName = n; }
    public void setColor(ChatColor c)    { this.color = c; }
    public void setTagColor(String t)    { this.tagColor = t; }

    /** Coloured display name for use in chat messages. */
    public String getColouredName() {
        return color + displayName;
    }

    @Override
    public String toString() {
        return "KMCTeam{id=" + id + ", members=" + members.size() + ", points=" + points + "}";
    }
}
