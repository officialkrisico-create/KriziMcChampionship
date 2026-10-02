package nl.kmc.mayhem.managers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * Generates nothing — every chunk is pure air. Overriding none of
 * {@link ChunkGenerator}'s generation hooks is the standard trick for an
 * empty/void world; Bukkit's default {@code ChunkGenerator} behaviour
 * already produces no terrain, biome noise, or structures.
 */
public final class VoidGenerator extends ChunkGenerator {

    /** A fixed spawn so the server never has to hunt for a "safe" one in a world with no ground. */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 100, 0.5);
    }
}
