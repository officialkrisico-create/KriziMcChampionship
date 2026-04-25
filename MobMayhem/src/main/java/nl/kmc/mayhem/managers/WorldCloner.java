package nl.kmc.mayhem.managers;

import nl.kmc.mayhem.MobMayhemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Clones the Mob Mayhem template world N times — one per team.
 *
 * <p>Per-team cloned worlds let mobs in one team's instance not bleed
 * into another team's. Each clone gets a unique name like
 * "mm_game_TEAMID_a1b2c3" so no collisions across simultaneous games
 * (in case a future build runs multiple matches).
 *
 * <p><b>Threading:</b> file-system copies run async (off main thread)
 * because they can take 1-3s for a 50MB+ template. Bukkit world load/
 * unload MUST be on the main thread, so the load step is dispatched
 * back to the main thread once copying completes.
 *
 * <p>For N teams, we kick off N async copies in parallel, then each
 * one's load step queues onto the main thread when its copy finishes.
 * A {@link AtomicInteger} counter tracks completion so we can fire
 * the final callback when all N are loaded.
 */
public class WorldCloner {

    private final MobMayhemPlugin plugin;

    /** Names of currently-cloned worlds, for cleanup. */
    private final Map<String, World> activeClones = new LinkedHashMap<>();

    public WorldCloner(MobMayhemPlugin plugin) { this.plugin = plugin; }

    public String getTemplateWorldName() {
        return plugin.getConfig().getString("world.template-name", "mm_template");
    }

    public boolean templateExists() {
        File templateDir = new File(Bukkit.getWorldContainer(), getTemplateWorldName());
        return templateDir.isDirectory();
    }

    /**
     * Clones the template world {@code teamCount} times in parallel.
     * Calls {@code onAllReady} on the main thread when every clone is
     * loaded, with a map of teamId → World. If any clone fails, the
     * map will only contain the successful ones — caller should check
     * the size against teamCount.
     *
     * @param teamIds the team ids to clone for (used as part of world name)
     * @param onAllReady callback fired on main thread when done
     */
    public void cloneForTeams(List<String> teamIds, Consumer<Map<String, World>> onAllReady) {
        if (!templateExists()) {
            plugin.getLogger().severe("Template world '" + getTemplateWorldName() + "' not found!");
            onAllReady.accept(Collections.emptyMap());
            return;
        }
        if (teamIds.isEmpty()) {
            onAllReady.accept(Collections.emptyMap());
            return;
        }

        plugin.getLogger().info("Cloning template " + teamIds.size() + " times "
                + "(one per team)... this may take a few seconds.");

        // Synchronized result map, populated as clones come online
        Map<String, World> result = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicInteger remaining = new AtomicInteger(teamIds.size());

        File source = new File(Bukkit.getWorldContainer(), getTemplateWorldName());

        for (String teamId : teamIds) {
            String worldName = "mm_game_" + sanitize(teamId) + "_"
                    + UUID.randomUUID().toString().substring(0, 6);
            File dest = new File(Bukkit.getWorldContainer(), worldName);

            // Async copy
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    copyDirectory(source.toPath(), dest.toPath());
                    File uid = new File(dest, "uid.dat");
                    if (uid.exists()) uid.delete();
                    File lock = new File(dest, "session.lock");
                    if (lock.exists()) lock.delete();
                } catch (IOException io) {
                    plugin.getLogger().severe("Copy failed for team " + teamId + ": " + io.getMessage());
                    if (remaining.decrementAndGet() == 0) {
                        Bukkit.getScheduler().runTask(plugin, () -> onAllReady.accept(result));
                    }
                    return;
                }

                // Main thread — load
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        WorldCreator wc = new WorldCreator(worldName);
                        World loaded = Bukkit.createWorld(wc);
                        if (loaded == null) {
                            plugin.getLogger().severe("Failed to load cloned world for team " + teamId);
                            deleteRecursive(dest);
                        } else {
                            loaded.setKeepSpawnInMemory(false);
                            loaded.setAutoSave(false);
                            loaded.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY,
                                    plugin.getConfig().getBoolean("game.keep-inventory", false));
                            loaded.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
                            loaded.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
                            loaded.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
                            loaded.setTime(18000); // midnight — lets Mob Mayhem own all spawns
                            loaded.setPVP(plugin.getConfig().getBoolean("game.pvp-enabled", false));
                            result.put(teamId, loaded);
                            activeClones.put(worldName, loaded);
                            plugin.getLogger().info("Cloned world ready: " + worldName + " (team " + teamId + ")");
                        }
                    } catch (Exception ex) {
                        plugin.getLogger().severe("Load failed for team " + teamId + ": " + ex.getMessage());
                    }

                    if (remaining.decrementAndGet() == 0) {
                        onAllReady.accept(result);
                    }
                });
            });
        }
    }

    /**
     * Unloads + deletes all currently-cloned worlds. Players in those
     * worlds are TPd to the main world before unloading.
     */
    public void disposeAll() {
        if (activeClones.isEmpty()) return;
        World mainWorld = Bukkit.getWorlds().get(0);

        // Snapshot to avoid concurrent modification
        List<Map.Entry<String, World>> snapshot = new ArrayList<>(activeClones.entrySet());
        activeClones.clear();

        for (var entry : snapshot) {
            World w = entry.getValue();
            String name = entry.getKey();
            if (w == null || w == mainWorld) continue;

            // Kick all players first
            var safe = mainWorld.getSpawnLocation();
            for (var p : w.getPlayers()) p.teleport(safe);

            boolean unloaded = Bukkit.unloadWorld(w, false);
            if (!unloaded) {
                plugin.getLogger().warning("Failed to unload world: " + name);
                continue;
            }

            File dir = new File(Bukkit.getWorldContainer(), name);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    deleteRecursive(dir);
                    plugin.getLogger().info("Deleted cloned world: " + name);
                } catch (Exception e) {
                    plugin.getLogger().warning("Delete failed for " + name + ": " + e.getMessage());
                }
            });
        }
    }

    public Map<String, World> getActiveClones() {
        return Collections.unmodifiableMap(activeClones);
    }

    // ----------------------------------------------------------------
    // FS helpers
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

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
