package nl.kmc.mayhem.managers;

import org.bukkit.generator.ChunkGenerator;

/**
 * Generates nothing — every chunk is pure air. Overriding none of
 * {@link ChunkGenerator}'s generation hooks is the standard trick for an
 * empty/void world; Bukkit's default {@code ChunkGenerator} behaviour
 * already produces no terrain, biome noise, or structures.
 */
public final class VoidGenerator extends ChunkGenerator {
}
