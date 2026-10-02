package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;

/**
 * Owns the single shared void world every Mob Mayhem match pastes its
 * captured arena into — one world for the whole server, with each team's
 * copy placed at a large, non-overlapping X offset ("pocket") so teams
 * never see or affect each other.
 *
 * <p>Replaces the old per-team full-world file clone: pasting a captured
 * region into an already-empty void is far cheaper than copying a whole
 * world folder, and (the actual bug this fixes) there's no leftover
 * template terrain for a bad spawn point to end up buried inside — outside
 * the pasted arena, the world is genuinely just air.
 */
public final class VoidWorldManager {

    private final MobMayhemPlugin plugin;
    private World voidWorld;

    public VoidWorldManager(MobMayhemPlugin plugin) {
        this.plugin = plugin;
    }

    /** The configured void world name (created on first use if it doesn't exist). */
    public String getVoidWorldName() {
        return plugin.getConfig().getString("world.void-world-name", "mm_void");
    }

    /** Returns the void world, creating it with an empty generator if needed. */
    public World getOrCreateVoidWorld() {
        if (voidWorld != null) return voidWorld;

        String name = getVoidWorldName();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            voidWorld = existing;
            applySettings(voidWorld);
            return voidWorld;
        }

        plugin.getLogger().info("[MobMayhem] Creating void world '" + name + "'...");
        WorldCreator creator = new WorldCreator(name).generator(new VoidGenerator());
        voidWorld = Bukkit.createWorld(creator);
        if (voidWorld != null) applySettings(voidWorld);
        return voidWorld;
    }

    private void applySettings(World world) {
        world.setKeepSpawnInMemory(false);
        world.setAutoSave(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, plugin.getConfig().getBoolean("game.keep-inventory", false));
        world.setTime(18000);
        world.setPVP(plugin.getConfig().getBoolean("game.pvp-enabled", false));
        world.setDifficulty(parseDifficulty(plugin.getConfig().getString("game.difficulty", "NORMAL")));
    }

    /**
     * A large, fixed spacing between team "pockets" in the void world —
     * generous enough that explosions, particles, and mob pathing from one
     * team's arena can never reach another's, regardless of arena size.
     */
    public int getPocketSpacing() {
        return plugin.getConfig().getInt("world.pocket-spacing", 10000);
    }

    /** Deterministic, collision-free paste origin for the Nth team pocket this game. */
    public org.bukkit.util.Vector pocketOffset(int pocketIndex) {
        return new org.bukkit.util.Vector((long) pocketIndex * getPocketSpacing(), 0, 0);
    }

    private static org.bukkit.Difficulty parseDifficulty(String name) {
        try { return org.bukkit.Difficulty.valueOf(name.toUpperCase()); }
        catch (Exception e) { return org.bukkit.Difficulty.NORMAL; }
    }
}
