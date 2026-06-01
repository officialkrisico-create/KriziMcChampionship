package nl.kmc.core.domain;

import org.bukkit.ChatColor;

import java.util.*;

/** Live mutable state for a KMC team. Backed by {@link nl.kmc.storage.model.StoredTeam} for persistence. */
public final class KMCTeam {

    private final String id;
    private final String displayName;
    private final ChatColor color;
    private final ChatColor tagColor;
    private final List<UUID> members = new ArrayList<>();

    private int points;
    private int wins;

    public KMCTeam(String id, String displayName, ChatColor color, ChatColor tagColor) {
        this.id          = id;
        this.displayName = displayName;
        this.color       = color;
        this.tagColor    = tagColor;
    }

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public ChatColor getColor()    { return color; }
    public ChatColor getTagColor() { return tagColor; }
    public int getPoints()         { return points; }
    public int getWins()           { return wins; }

    public String getColouredName() { return color + displayName; }

    public List<UUID> getMembers() { return Collections.unmodifiableList(members); }

    public void addMember(UUID uuid)    { if (!members.contains(uuid)) members.add(uuid); }
    public void removeMember(UUID uuid) { members.remove(uuid); }
    public boolean hasMember(UUID uuid) { return members.contains(uuid); }

    public void addPoints(int amount)   { this.points = Math.max(0, this.points + amount); }
    public void setPoints(int points)   { this.points = Math.max(0, points); }
    public void addWin()                { this.wins++; }
    public void setWins(int wins)       { this.wins = Math.max(0, wins); }

    public void softReset() { this.points = 0; this.wins = 0; }

    @Override public String toString() { return "KMCTeam{id='" + id + "', points=" + points + "}"; }
}
