package nl.kmc.skywars.models;

import org.bukkit.Location;

/**
 * One SkyWars island. Each team has its own.
 *
 * <p>Just stores the spawn location. Chests on the island are
 * detected dynamically by the ChestStocker scanning a region around
 * each island spawn.
 */
public class Island {

    private final String   id;
    private final Location spawn;
    private final int      chestSearchRadius;  // blocks around spawn to look for chests

    public Island(String id, Location spawn, int chestSearchRadius) {
        this.id                = id;
        this.spawn             = spawn.clone();
        this.chestSearchRadius = chestSearchRadius;
    }

    public String   getId()                { return id; }
    public Location getSpawn()             { return spawn.clone(); }
    public int      getChestSearchRadius() { return chestSearchRadius; }
}
