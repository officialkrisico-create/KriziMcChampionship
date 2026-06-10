package nl.kmc.speedbuild.schematic;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import nl.kmc.kmccore.KMCCore;
import org.bukkit.Location;

/**
 * Thin wrapper over KMCCore's {@link nl.kmc.kmccore.managers.SchematicManager},
 * so Speed Build shares the same schematics folder and WorldEdit plumbing as
 * the rest of KMC instead of duplicating it.
 */
public final class SchematicLoader {

    private final KMCCore core;

    public SchematicLoader(KMCCore core) { this.core = core; }

    public boolean isWorldEditAvailable() {
        return core.getSchematicManager().isWorldEditAvailable();
    }

    /** Loads (and caches) a schematic clipboard, or {@code null} if missing/invalid. */
    public Clipboard load(String schematicFile) {
        return core.getSchematicManager().loadClipboard(schematicFile);
    }

    public boolean exists(String schematicFile) {
        return new java.io.File(core.getSchematicManager().getSchematicFolder(), schematicFile).exists();
    }

    /** Pastes a schematic as a visible reference, min corner at {@code min}. */
    public void pasteBlueprint(String schematicFile, Location min) {
        Clipboard c = load(schematicFile);
        if (c != null) WorldEditAdapter.pasteAtMinCorner(c, min, true);
    }
}
