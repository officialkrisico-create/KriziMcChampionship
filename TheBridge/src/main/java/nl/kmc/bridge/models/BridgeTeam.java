package nl.kmc.bridge.models;

import org.bukkit.ChatColor;
import org.bukkit.Location;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One Bridge team. Each team has:
 * <ul>
 *   <li>Spawn location (where members spawn at game start + after dying)</li>
 *   <li>Goal region (the hole opponents jump into to score against you)</li>
 *   <li>Wool color for blocks + chat color</li>
 *   <li>Member set</li>
 *   <li>Goal counter (goals scored by THIS team against opponents)</li>
 * </ul>
 *
 * <p>Bridge is typically 2 teams (Red vs Blue) but supports up to 4
 * (each team's goal hole is on a different side of the map).
 */
public class BridgeTeam {

    private final String       id;             // matches KMCCore team id
    private final String       displayName;
    private final ChatColor    chatColor;
    private final org.bukkit.Material woolMaterial;
    private final Location     spawn;
    private final Location     goalPos1;
    private final Location     goalPos2;

    private final Set<UUID> members = new HashSet<>();
    private int goalsScored;        // goals THIS team scored against others

    public BridgeTeam(String id, String displayName, ChatColor chatColor,
                      org.bukkit.Material woolMaterial,
                      Location spawn, Location goalPos1, Location goalPos2) {
        this.id           = id;
        this.displayName  = displayName;
        this.chatColor    = chatColor;
        this.woolMaterial = woolMaterial;
        this.spawn        = spawn;
        this.goalPos1     = goalPos1;
        this.goalPos2     = goalPos2;
    }

    public String                getId()           { return id; }
    public String                getDisplayName()  { return displayName; }
    public ChatColor             getChatColor()    { return chatColor; }
    public org.bukkit.Material   getWoolMaterial() { return woolMaterial; }
    public Location              getSpawn()        { return spawn != null ? spawn.clone() : null; }
    public Location              getGoalPos1()     { return goalPos1; }
    public Location              getGoalPos2()     { return goalPos2; }

    public Set<UUID>             getMembers()      { return members; }
    public void                  addMember(UUID u) { members.add(u); }
    public void                  removeMember(UUID u) { members.remove(u); }

    public int                   getGoalsScored()  { return goalsScored; }
    public void                  addGoal()         { goalsScored++; }
    public void                  resetGoals()      { goalsScored = 0; }

    /** Did the given location enter THIS team's goal region? */
    public boolean isInGoalRegion(Location loc) {
        if (loc == null || goalPos1 == null || goalPos2 == null) return false;
        if (!loc.getWorld().equals(goalPos1.getWorld())) return false;
        double minX = Math.min(goalPos1.getX(), goalPos2.getX());
        double maxX = Math.max(goalPos1.getX(), goalPos2.getX()) + 1;
        double minY = Math.min(goalPos1.getY(), goalPos2.getY());
        double maxY = Math.max(goalPos1.getY(), goalPos2.getY()) + 1;
        double minZ = Math.min(goalPos1.getZ(), goalPos2.getZ());
        double maxZ = Math.max(goalPos1.getZ(), goalPos2.getZ()) + 1;
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
}
