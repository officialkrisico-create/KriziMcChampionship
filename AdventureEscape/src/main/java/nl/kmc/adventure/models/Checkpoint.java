package nl.kmc.adventure.models;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A checkpoint on the race track.
 *
 * <p>This class supports TWO usage patterns:
 *
 * <p><b>Legacy / lap-tracking checkpoint</b> — used by the original
 * lap-counting system (ArenaManager, LineCrossListener). A 2-corner
 * trigger box with an integer index. Players must pass through ALL
 * checkpoints in order before completing a lap (anti-shortcut).
 * Constructor: {@link #Checkpoint(int, Location, Location)}.
 *
 * <p><b>Named/respawn checkpoint</b> — used by the new CP+OOB+TrialKey
 * feature pack (CheckpointManager, OutOfBoundsListener). Identified by
 * a string name, has a respawn location, optional trigger box, and
 * scoped out-of-bounds boxes.
 * Constructor: {@link #Checkpoint(String, Location)}.
 *
 * <p>The legacy getters {@link #getPos1()} / {@link #getPos2()} /
 * {@link #getIndex()} continue to work for legacy-constructed CPs.
 * For named CPs, {@link #getPos1()} / {@link #getPos2()} return the
 * trigger pos1/pos2 (or null if no trigger set), and {@link #getIndex()}
 * returns -1.
 */
public class Checkpoint {

    // ---- Legacy fields ---------------------------------------------
    private final int index;          // -1 for named CPs

    // ---- Named-CP fields -------------------------------------------
    private final String   name;       // null for legacy CPs
    private final Location respawn;    // null for legacy CPs

    // ---- Trigger box (used by both styles) -------------------------
    private Location triggerPos1;
    private Location triggerPos2;

    // ---- OOB boxes (named-CP feature) ------------------------------
    private final List<OutOfBoundsBox> oobBoxes = new ArrayList<>();

    // ----------------------------------------------------------------
    // Legacy constructor — int index + 2-corner box
    // ----------------------------------------------------------------
    public Checkpoint(int index, Location pos1, Location pos2) {
        this.index       = index;
        this.name        = null;
        this.respawn     = null;
        this.triggerPos1 = pos1;
        this.triggerPos2 = pos2;
    }

    // ----------------------------------------------------------------
    // New constructor — string name + respawn loc, trigger optional
    // ----------------------------------------------------------------
    public Checkpoint(String name, Location respawn) {
        this.index       = -1;
        this.name        = name;
        this.respawn     = respawn != null ? respawn.clone() : null;
        this.triggerPos1 = null;
        this.triggerPos2 = null;
    }

    // ----------------------------------------------------------------
    // Legacy API
    // ----------------------------------------------------------------

    /** Legacy 1-indexed checkpoint number. Returns -1 for named CPs. */
    public int getIndex() { return index; }

    /** Legacy: trigger pos1 (also exposed as {@link #getTriggerPos1()}). */
    public Location getPos1() { return triggerPos1; }

    /** Legacy: trigger pos2 (also exposed as {@link #getTriggerPos2()}). */
    public Location getPos2() { return triggerPos2; }

    /** True if {@code loc} is inside this CP's trigger box. */
    public boolean contains(Location loc) {
        if (!hasTrigger()) return false;
        if (loc == null) return false;
        if (!loc.getWorld().equals(triggerPos1.getWorld())) return false;

        double minX = Math.min(triggerPos1.getX(), triggerPos2.getX());
        double maxX = Math.max(triggerPos1.getX(), triggerPos2.getX()) + 1;
        double minY = Math.min(triggerPos1.getY(), triggerPos2.getY());
        double maxY = Math.max(triggerPos1.getY(), triggerPos2.getY()) + 1;
        double minZ = Math.min(triggerPos1.getZ(), triggerPos2.getZ());
        double maxZ = Math.max(triggerPos1.getZ(), triggerPos2.getZ()) + 1;

        return loc.getX() >= minX && loc.getX() <= maxX
                && loc.getY() >= minY && loc.getY() <= maxY
                && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    // ----------------------------------------------------------------
    // New API — named CP / respawn / trigger / OOB boxes
    // ----------------------------------------------------------------

    public String   getName()    { return name; }
    public Location getRespawn() { return respawn != null ? respawn.clone() : null; }

    /** True if this CP has a trigger box configured. */
    public boolean hasTrigger() {
        return triggerPos1 != null && triggerPos2 != null;
    }

    public Location getTriggerPos1() { return triggerPos1; }
    public Location getTriggerPos2() { return triggerPos2; }

    /** Sets/replaces the trigger box. Used by /ae setcheckpointtrigger. */
    public void setTrigger(Location pos1, Location pos2) {
        this.triggerPos1 = pos1 != null ? pos1.clone() : null;
        this.triggerPos2 = pos2 != null ? pos2.clone() : null;
    }

    public List<OutOfBoundsBox> getOobBoxes() {
        return Collections.unmodifiableList(oobBoxes);
    }

    public void addOobBox(OutOfBoundsBox box) {
        if (box == null) return;
        // Replace existing box with same name if present
        removeOobBox(box.getName());
        oobBoxes.add(box);
    }

    public boolean removeOobBox(String boxName) {
        if (boxName == null) return false;
        Iterator<OutOfBoundsBox> it = oobBoxes.iterator();
        while (it.hasNext()) {
            if (boxName.equalsIgnoreCase(it.next().getName())) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}