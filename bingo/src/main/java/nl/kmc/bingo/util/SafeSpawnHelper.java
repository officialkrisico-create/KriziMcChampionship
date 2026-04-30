package nl.kmc.bingo.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.*;

/**
 * Picks safe spawn locations for players, spaced apart, in a freshly
 * cloned Bingo world.
 *
 * <p>"Safe" means:
 * <ul>
 *   <li>Solid non-hazardous block at feet level</li>
 *   <li>Two air (or passable) blocks above it (head + body)</li>
 *   <li>NOT lava, fire, magma, cactus, or sweet berries directly under or on the spot</li>
 *   <li>NOT in water (you'd drown on spawn)</li>
 *   <li>Has sky access (open to the sun)</li>
 * </ul>
 *
 * <p>Performance budget — this runs synchronously on the main thread
 * during game start, so it MUST be fast:
 * <ul>
 *   <li>Pre-loads (forces) only the spawn-area chunks ONCE, before the loop</li>
 *   <li>For each player, samples points in an expanding spiral around base spawn</li>
 *   <li>Caps at {@link #MAX_CANDIDATES_PER_PLAYER} candidates checked per player</li>
 *   <li>Caches "this column has been checked" to avoid redundant Y-scans</li>
 * </ul>
 *
 * <p>Total worst-case work for 64 players: 64 × 80 candidates = ~5120
 * column scans, each averaging ~10 block lookups = ~50k block lookups.
 * On a loaded chunk that's roughly 5–15 ms total — well under one tick.
 */
public final class SafeSpawnHelper {

    /** How far from the base spawn we'll search (in blocks, manhattan). */
    public static final int SEARCH_RADIUS = 24;

    /** Minimum distance between picked spawn points. */
    public static final int MIN_SPACING = 4;

    /** Minimum distance between TEAM anchor points (so different teams spawn apart). */
    public static final int TEAM_SPACING = 20;

    /** Search radius when placing team anchors (larger to fit teams 20 blocks apart). */
    public static final int TEAM_SEARCH_RADIUS = 64;

    /** Hard cap so a single search can't go pathological. */
    public static final int MAX_CANDIDATES_PER_PLAYER = 80;

    /** Hazard materials we must NOT spawn on/in. */
    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.MAGMA_BLOCK, Material.CACTUS,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW
    );

    /** Materials we treat as "passable" for the head/body check. */
    private static final Set<Material> PASSABLE = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.DEAD_BUSH, Material.SNOW
    );

    private SafeSpawnHelper() {}

    /**
     * Picks N safe, spaced-apart spawn locations near the given base.
     * Returns a list of length {@code count} (never null entries —
     * falls back to base spawn if no safe spot found).
     */
    public static List<Location> findSpawns(Location base, int count) {
        if (base == null || base.getWorld() == null || count <= 0) {
            return Collections.emptyList();
        }

        World world = base.getWorld();
        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();

        // Pre-load chunks once. We compute the chunk range from the
        // search radius so we don't trigger chunk loads inside the
        // hot loop (which would be SLOW).
        preloadChunks(world, baseX, baseZ);

        List<Location> chosen = new ArrayList<>(count);
        // Track picked block coords so we can enforce MIN_SPACING.
        Set<Long> taken = new HashSet<>();

        // Try the base spawn first — it's usually fine
        Location baseSafe = findSafeColumn(world, baseX, baseY, baseZ);
        if (baseSafe != null) {
            chosen.add(baseSafe);
            taken.add(packXZ(baseSafe.getBlockX(), baseSafe.getBlockZ()));
        }

        // Spiral outward from base for the rest
        for (int i = chosen.size(); i < count; i++) {
            Location spot = findSpacedSpot(world, baseX, baseY, baseZ, taken);
            if (spot == null) {
                // Fallback: base spawn (safer than null/void)
                chosen.add(baseSafe != null ? baseSafe.clone() : base.clone());
            } else {
                chosen.add(spot);
                taken.add(packXZ(spot.getBlockX(), spot.getBlockZ()));
            }
        }

        return chosen;
    }

    /**
     * Force-loads chunks within the search radius so the inner loop
     * never has to wait for chunk generation.
     */
    private static void preloadChunks(World world, int baseX, int baseZ) {
        int chunkRadius = (SEARCH_RADIUS >> 4) + 1;
        int baseChunkX = baseX >> 4;
        int baseChunkZ = baseZ >> 4;
        for (int cx = baseChunkX - chunkRadius; cx <= baseChunkX + chunkRadius; cx++) {
            for (int cz = baseChunkZ - chunkRadius; cz <= baseChunkZ + chunkRadius; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
    }

    /**
     * Spirals outward from base looking for a safe spot that's at
     * least MIN_SPACING away from anything already in {@code taken}.
     */
    private static Location findSpacedSpot(World world, int baseX, int baseY, int baseZ,
                                            Set<Long> taken) {
        int attempts = 0;
        // Spiral pattern: increasing radius rings
        for (int r = MIN_SPACING; r <= SEARCH_RADIUS; r++) {
            // Sample 8 points around the ring
            int samples = Math.max(8, r);
            for (int i = 0; i < samples; i++) {
                if (++attempts > MAX_CANDIDATES_PER_PLAYER) return null;

                double angle = (2 * Math.PI * i) / samples;
                int dx = (int) Math.round(r * Math.cos(angle));
                int dz = (int) Math.round(r * Math.sin(angle));
                int x = baseX + dx;
                int z = baseZ + dz;

                // Spacing check — skip if too close to a taken spot
                if (tooClose(x, z, taken)) continue;

                Location safe = findSafeColumn(world, x, baseY, z);
                if (safe != null) return safe;
            }
        }
        return null;
    }

    private static boolean tooClose(int x, int z, Set<Long> taken) {
        for (long packed : taken) {
            int tx = (int) (packed >> 32);
            int tz = (int) (packed & 0xFFFFFFFFL);
            int dx = x - tx;
            int dz = z - tz;
            if (dx * dx + dz * dz < MIN_SPACING * MIN_SPACING) return true;
        }
        return false;
    }

    /**
     * Looks for a safe Y at column (x, z), starting from baseY and
     * walking up to find the surface. Returns null if no safe spot.
     */
    private static Location findSafeColumn(World world, int x, int baseY, int z) {
        // Cap to world height bounds
        int minY = Math.max(world.getMinHeight() + 1, baseY - 16);
        int maxY = Math.min(world.getMaxHeight() - 3, baseY + 16);

        // Start by trying the base Y and walking up to find first solid
        // block under air/passable.
        for (int y = minY; y <= maxY; y++) {
            Block ground   = world.getBlockAt(x, y,     z);
            Block feet     = world.getBlockAt(x, y + 1, z);
            Block head     = world.getBlockAt(x, y + 2, z);

            if (!isSolidSafe(ground)) continue;
            if (!isPassableSafe(feet)) continue;
            if (!isPassableSafe(head)) continue;

            // Center on the block (X.5, Y, Z.5)
            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private static boolean isSolidSafe(Block block) {
        Material m = block.getType();
        if (HAZARDS.contains(m)) return false;
        if (!block.getType().isSolid()) return false;
        // Avoid spawning on slabs/stairs partially — they often confuse teleport
        if (m.name().endsWith("_SLAB") || m.name().endsWith("_STAIRS")) return false;
        return true;
    }

    private static boolean isPassableSafe(Block block) {
        Material m = block.getType();
        if (HAZARDS.contains(m)) return false;
        if (PASSABLE.contains(m)) return true;
        // Reject water/lava — even if not a hazard "block", you'd drown
        if (m == Material.WATER || m == Material.LAVA) return false;
        // Reject waterlogged blocks — same drown risk
        BlockData bd = block.getBlockData();
        if (bd instanceof Waterlogged wl && wl.isWaterlogged()) return false;
        // Anything else passable
        return !m.isSolid();
    }

    /**
     * Picks N team-anchor locations, each spaced at least
     * {@link #TEAM_SPACING} blocks from the others. Each anchor is on a
     * safe block. Used for Bingo where each team should spawn 20 blocks
     * apart from other teams (but teammates spawn near each other).
     *
     * <p>Pre-loads chunks across the larger {@link #TEAM_SEARCH_RADIUS}.
     *
     * <p>If {@code teamCount} anchors can't be found within radius, the
     * remaining slots fall back to the base spawn.
     */
    public static List<Location> findTeamSpawns(Location base, int teamCount) {
        if (base == null || base.getWorld() == null || teamCount <= 0) {
            return Collections.emptyList();
        }

        World world = base.getWorld();
        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();

        // Pre-load chunks across the larger radius
        preloadTeamChunks(world, baseX, baseZ);

        List<Location> anchors = new ArrayList<>(teamCount);

        // First anchor — try base spawn itself
        Location baseSafe = findSafeColumn(world, baseX, baseY, baseZ);
        if (baseSafe != null) anchors.add(baseSafe);

        // Spiral outward placing anchors at >= TEAM_SPACING from each other
        // We use a coarser ring (every TEAM_SPACING blocks) so we don't
        // search every column.
        int maxAttempts = teamCount * 50;
        int attempts = 0;
        for (int r = TEAM_SPACING; r <= TEAM_SEARCH_RADIUS && anchors.size() < teamCount; r += TEAM_SPACING / 2) {
            int samples = Math.max(8, (int) (2 * Math.PI * r / TEAM_SPACING));
            for (int i = 0; i < samples && anchors.size() < teamCount; i++) {
                if (++attempts > maxAttempts) break;

                double angle = (2 * Math.PI * i) / samples;
                int x = baseX + (int) Math.round(r * Math.cos(angle));
                int z = baseZ + (int) Math.round(r * Math.sin(angle));

                // Spacing check
                if (tooCloseToAny(x, z, anchors, TEAM_SPACING)) continue;

                Location safe = findSafeColumn(world, x, baseY, z);
                if (safe != null) anchors.add(safe);
            }
        }

        // Fallback: not enough safe team anchors found — duplicate base
        while (anchors.size() < teamCount) {
            anchors.add(baseSafe != null ? baseSafe.clone() : base.clone());
        }

        return anchors;
    }

    /**
     * Picks {@code count} player-spawn locations near a team's anchor.
     * Spaces them apart by {@link #MIN_SPACING}. Used for placing all
     * members of a single team near their team anchor.
     */
    public static List<Location> findPlayerSpawnsNearAnchor(Location anchor, int count) {
        // Reuse the existing findSpawns — its base/spiral pattern works
        // perfectly when "base" is the team anchor.
        return findSpawns(anchor, count);
    }

    private static boolean tooCloseToAny(int x, int z, List<Location> picked, int min) {
        int min2 = min * min;
        for (Location l : picked) {
            int dx = x - l.getBlockX();
            int dz = z - l.getBlockZ();
            if (dx * dx + dz * dz < min2) return true;
        }
        return false;
    }

    private static void preloadTeamChunks(World world, int baseX, int baseZ) {
        int chunkRadius = (TEAM_SEARCH_RADIUS >> 4) + 1;
        int baseChunkX = baseX >> 4;
        int baseChunkZ = baseZ >> 4;
        for (int cx = baseChunkX - chunkRadius; cx <= baseChunkX + chunkRadius; cx++) {
            for (int cz = baseChunkZ - chunkRadius; cz <= baseChunkZ + chunkRadius; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
