package nl.kmc.skywars.models;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One SkyWars island.
 *
 * <p>Each island has:
 * <ul>
 *   <li>An identifier ({@code id})</li>
 *   <li>A primary spawn location ({@code spawn}) — the default fallback
 *       and chest-stocker search center</li>
 *   <li>An optional {@code teamId} — if set, the KMCCore team with this
 *       id will always be placed on this island</li>
 *   <li>A list of {@code playerSpawns} — per-player spawn positions on
 *       the island. If empty, all players on the team spawn at the
 *       primary spawn. If populated, players are distributed across
 *       these positions (round-robin)</li>
 *   <li>{@code chestSearchRadius} — how far around the primary spawn
 *       to search for chests at game start</li>
 * </ul>
 */
public class Island {

    private final String   id;
    private Location       spawn;
    private final int      chestSearchRadius;
    private String         teamId;
    private final List<Location> playerSpawns = new ArrayList<>();

    /** Legacy 3-arg constructor — no team binding, no player spawns. */
    public Island(String id, Location spawn, int chestSearchRadius) {
        this.id                = id;
        this.spawn             = spawn != null ? spawn.clone() : null;
        this.chestSearchRadius = chestSearchRadius;
        this.teamId            = null;
    }

    public String   getId()                { return id; }
    public Location getSpawn()             { return spawn != null ? spawn.clone() : null; }
    public void     setSpawn(Location l)   { this.spawn = l != null ? l.clone() : null; }
    public int      getChestSearchRadius() { return chestSearchRadius; }

    public String   getTeamId()            { return teamId; }
    public void     setTeamId(String t)    { this.teamId = (t == null || t.isEmpty()) ? null : t; }

    /** Returns an unmodifiable copy of the per-player spawn list. */
    public List<Location> getPlayerSpawns() {
        List<Location> copy = new ArrayList<>(playerSpawns.size());
        for (Location l : playerSpawns) copy.add(l != null ? l.clone() : null);
        return Collections.unmodifiableList(copy);
    }

    public void addPlayerSpawn(Location l) {
        if (l != null) playerSpawns.add(l.clone());
    }

    public void clearPlayerSpawns() { playerSpawns.clear(); }

    public int playerSpawnCount() { return playerSpawns.size(); }

    /**
     * Picks a player spawn for the {@code memberIndex}-th member of
     * a team. If no per-player spawns are configured, returns the
     * primary spawn (so all members pile on top — same as legacy
     * behavior).
     *
     * <p>If there are MORE members than configured spawns, wraps
     * around (modulo) — 5 members on a 4-spawn island means two
     * members share spawn 0.
     */
    public Location getSpawnForMember(int memberIndex) {
        if (playerSpawns.isEmpty()) return getSpawn();
        Location l = playerSpawns.get(memberIndex % playerSpawns.size());
        return l != null ? l.clone() : getSpawn();
    }
}
