package nl.kmc.bingo.managers;

import nl.kmc.bingo.BingoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/**
 * Manages the cloned game world for each Bingo match.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Admin pre-builds a "template" world (e.g. fresh survival seed).</li>
 *   <li>Per game: file-system-copy template → "bingo_game_&lt;random&gt;",
 *       load via Bukkit, players play, unload + delete on cleanup.</li>
 * </ol>
 *
 * <p>File copying is done OFF the main thread because it can take a
 * second or two for a 100MB+ world. Bukkit world load/unload MUST be
 * on the main thread.
 */
public class WorldManager {

    private final BingoPlugin plugin;
    private World gameWorld;
    private String gameWorldName;

    public WorldManager(BingoPlugin plugin) { this.plugin = plugin; }

    /**
     * Saves the currently-loaded template world name to config.
     * Admin command: /bingo settemplate &lt;worldname&gt;.
     */
    public void setTemplateWorld(String name) {
        plugin.getConfig().set("world.template-name", name);
        plugin.saveConfig();
    }

    public String getTemplateWorldName() {
        return plugin.getConfig().getString("world.template-name", "bingo_template");
    }

    public boolean templateExists() {
        File templateDir = new File(Bukkit.getWorldContainer(), getTemplateWorldName());
        return templateDir.isDirectory();
    }

    public World getGameWorld() { return gameWorld; }
    public boolean hasGameWorld() { return gameWorld != null; }

    public Location getDefaultSpawn() {
        if (gameWorld == null) return null;
        var sec = plugin.getConfig().getConfigurationSection("world.default-spawn");
        if (sec == null) return gameWorld.getSpawnLocation();
        return new Location(gameWorld,
                sec.getDouble("x", 0),
                sec.getDouble("y", 65),
                sec.getDouble("z", 0),
                (float) sec.getDouble("yaw", 0),
                (float) sec.getDouble("pitch", 0));
    }

    // ----------------------------------------------------------------
    // Game world creation
    // ----------------------------------------------------------------

    /**
     * Async-clones the template into a unique game world, then loads
     * it on the main thread. Calls callback with the loaded World on
     * success, or null on failure.
     */
    public void createGameWorldAsync(java.util.function.Consumer<World> callback) {
        if (!templateExists()) {
            plugin.getLogger().severe("Template world '" + getTemplateWorldName() + "' not found!");
            callback.accept(null);
            return;
        }
        if (gameWorld != null) {
            plugin.getLogger().warning("Game world already loaded — disposing first.");
            disposeGameWorld();
        }

        String newName = "bingo_game_" + UUID.randomUUID().toString().substring(0, 8);
        File source = new File(Bukkit.getWorldContainer(), getTemplateWorldName());
        File dest   = new File(Bukkit.getWorldContainer(), newName);

        plugin.getLogger().info("Cloning template '" + getTemplateWorldName() + "' → '" + newName + "'");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyDirectory(source.toPath(), dest.toPath());
                // Delete uid.dat so Bukkit assigns a fresh UUID
                File uid = new File(dest, "uid.dat");
                if (uid.exists()) uid.delete();
                // Delete session.lock too
                File lock = new File(dest, "session.lock");
                if (lock.exists()) lock.delete();

                // Load on the main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        WorldCreator wc = new WorldCreator(newName);
                        World loaded = Bukkit.createWorld(wc);
                        if (loaded == null) {
                            plugin.getLogger().severe("Failed to load cloned world!");
                            deleteRecursive(dest);
                            callback.accept(null);
                            return;
                        }
                        loaded.setKeepSpawnInMemory(false);
                        loaded.setAutoSave(false);
                        loaded.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY,
                                plugin.getConfig().getBoolean("game.keep-inventory", true));
                        loaded.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, true);
                        loaded.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
                        loaded.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, true);
                        loaded.setPVP(plugin.getConfig().getBoolean("game.pvp-enabled", false));

                        gameWorld     = loaded;
                        gameWorldName = newName;
                        plugin.getLogger().info("Game world loaded: " + newName);
                        callback.accept(loaded);
                    } catch (Exception ex) {
                        plugin.getLogger().severe("Error loading cloned world: " + ex.getMessage());
                        callback.accept(null);
                    }
                });
            } catch (IOException io) {
                plugin.getLogger().severe("Failed to copy world files: " + io.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
            }
        });
    }

    /**
     * Unloads the game world and deletes its files. Players in the
     * world are kicked back to the main world first.
     */
    public void disposeGameWorld() {
        if (gameWorld == null) return;
        World disposing = gameWorld;
        String name = gameWorldName;

        // Kick all players to the default world
        World mainWorld = Bukkit.getWorlds().get(0);
        if (mainWorld == disposing) {
            plugin.getLogger().severe("Refusing to dispose main world!");
            return;
        }
        Location safe = mainWorld.getSpawnLocation();
        for (Player p : disposing.getPlayers()) {
            p.teleport(safe);
        }

        // Unload
        boolean unloaded = Bukkit.unloadWorld(disposing, false);
        gameWorld = null;
        gameWorldName = null;

        if (!unloaded) {
            plugin.getLogger().warning("Failed to unload world '" + name + "' cleanly.");
            return;
        }

        // Delete directory async
        File dir = new File(Bukkit.getWorldContainer(), name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteRecursive(dir);
                plugin.getLogger().info("Deleted game world directory: " + name);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to delete world dir " + name + ": " + e.getMessage());
            }
        });
    }

    // ----------------------------------------------------------------
    // File-system helpers
    // ----------------------------------------------------------------

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Path destDir = target.resolve(rel);
                if (!Files.exists(destDir)) Files.createDirectories(destDir);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path destFile = target.resolve(rel);
                Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
