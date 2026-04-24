package nl.kmc.kmccore.managers;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Location;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads, pastes, and clears arena schematics.
 *
 * <p>Each game has exactly ONE arena schematic configured in {@code config.yml}
 * under {@code games.list.<gameId>.schematic} (a filename relative to
 * {@code plugins/KMCCore/schematics/}).
 *
 * <p><b>Reset strategy:</b> Before each paste we save the clipboard so
 * we know the exact bounding box of the arena. To "reset" we simply
 * re-paste the same schematic at the same origin — this overwrites any
 * player-placed blocks and any broken terrain.
 *
 * <p>The origin location for each game is configured at
 * {@code games.list.<gameId>.arena-origin}. Set it in-game with
 * {@code /kmcgame setorigin <gameId>}.
 *
 * <p>Requires WorldEdit or FastAsyncWorldEdit. If neither is installed
 * the manager logs a warning and schematic features become no-ops.
 */
public class SchematicManager {

    private final KMCCore plugin;
    private final File    schematicFolder;

    /** Cached clipboards, keyed by schematic filename. */
    private final Map<String, Clipboard> cache = new HashMap<>();

    private boolean worldEditAvailable;

    // ----------------------------------------------------------------

    public SchematicManager(KMCCore plugin) {
        this.plugin          = plugin;
        this.schematicFolder = new File(plugin.getDataFolder(), "schematics");
        if (!schematicFolder.exists()) schematicFolder.mkdirs();

        // Detect WorldEdit / FAWE
        worldEditAvailable = plugin.getServer().getPluginManager().getPlugin("WorldEdit") != null
                          || plugin.getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null;

        if (worldEditAvailable) {
            plugin.getLogger().info("SchematicManager ready — WorldEdit detected.");
        } else {
            plugin.getLogger().warning("WorldEdit not found! Arena schematic features disabled.");
            plugin.getLogger().warning("Install WorldEdit or FastAsyncWorldEdit to enable automatic arena loading.");
        }
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Pastes a schematic at the specified location.
     *
     * <p>Runs on the main thread — schematic paste IS blocking.
     * For very large arenas consider calling this async via FAWE's
     * async paste API, but for most use cases this is fine.
     *
     * @param schematicName filename like {@code "skywars.schem"}
     * @param origin        world location where the schematic's minimum
     *                      corner will be placed
     * @return {@code true} on success
     */
    public boolean pasteSchematic(String schematicName, Location origin) {
        if (!worldEditAvailable) {
            plugin.getLogger().warning("Cannot paste schematic — WorldEdit not installed.");
            return false;
        }
        if (origin == null) {
            plugin.getLogger().warning("Cannot paste schematic — origin is null.");
            return false;
        }

        Clipboard clipboard = loadClipboard(schematicName);
        if (clipboard == null) return false;

        World weWorld = BukkitAdapter.adapt(origin.getWorld());
        BlockVector3 pasteLoc = BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());

        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(pasteLoc)
                    .ignoreAirBlocks(false)
                    .build();

            Operations.complete(operation);
            plugin.getLogger().info("Pasted schematic '" + schematicName + "' at "
                    + origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ());
            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to paste schematic " + schematicName, e);
            return false;
        }
    }

    /**
     * Resets an arena by re-pasting its schematic.
     * This is literally just {@link #pasteSchematic} — the fresh paste
     * overwrites any changes made during the game.
     *
     * @param schematicName filename
     * @param origin        original origin location
     */
    public boolean resetArena(String schematicName, Location origin) {
        plugin.getLogger().info("Resetting arena '" + schematicName + "'...");
        return pasteSchematic(schematicName, origin);
    }

    // ----------------------------------------------------------------
    // Schematic loading
    // ----------------------------------------------------------------

    /**
     * Loads and caches a schematic clipboard.
     * Subsequent calls return the cached instance.
     *
     * @param schematicName filename within the schematics folder
     * @return the clipboard, or {@code null} if not found / invalid
     */
    public Clipboard loadClipboard(String schematicName) {
        if (cache.containsKey(schematicName)) return cache.get(schematicName);

        File file = new File(schematicFolder, schematicName);
        if (!file.exists()) {
            plugin.getLogger().warning("Schematic file not found: " + file.getAbsolutePath());
            return null;
        }

        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            plugin.getLogger().warning("Unknown schematic format: " + schematicName);
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file);
             ClipboardReader reader = format.getReader(fis)) {
            Clipboard clipboard = reader.read();
            cache.put(schematicName, clipboard);
            plugin.getLogger().info("Loaded schematic: " + schematicName);
            return clipboard;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load schematic " + schematicName, e);
            return null;
        }
    }

    /** Clears the clipboard cache — call after schematic files change. */
    public void clearCache() {
        cache.clear();
    }

    // ----------------------------------------------------------------
    // Helpers for game configs
    // ----------------------------------------------------------------

    /**
     * Reads {@code games.list.<gameId>.schematic} from config.
     * @return filename, or {@code null} if not configured
     */
    public String getSchematicForGame(String gameId) {
        return plugin.getConfig().getString("games.list." + gameId + ".schematic");
    }

    /**
     * Reads {@code games.list.<gameId>.arena-origin} from config.
     * @return Location, or {@code null} if not set
     */
    public Location getOriginForGame(String gameId) {
        return plugin.getConfig().getLocation("games.list." + gameId + ".arena-origin");
    }

    /** Saves the origin location for a game. */
    public void setOriginForGame(String gameId, Location origin) {
        plugin.getConfig().set("games.list." + gameId + ".arena-origin", origin);
        plugin.saveConfig();
    }

    /** @return {@code true} if WorldEdit/FAWE is installed. */
    public boolean isWorldEditAvailable() {
        return worldEditAvailable;
    }

    /** @return the schematics folder so admins know where to drop files. */
    public File getSchematicFolder() {
        return schematicFolder;
    }
}
