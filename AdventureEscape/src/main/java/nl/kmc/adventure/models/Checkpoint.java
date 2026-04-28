package nl.kmc.adventure.models;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * One checkpoint in an Adventure Escape race.
 *
 * <p>A checkpoint has:
 * <ul>
 *   <li>A unique name (used in commands and config keys)</li>
 *   <li>A respawn location (where players are sent back to)</li>
 *   <li>An optional trigger region — entering this region marks the
 *       checkpoint as reached (if null, players must be teleported here
 *       manually or via the Trial Key from a previous checkpoint)</li>
 *   <li>Zero or more out-of-bounds boxes — falling into any one of them
 *       teleports the player back to this checkpoint</li>
 * </ul>
 */
public class Checkpoint {

    private final String name;
    private final Location respawn;
    private Location triggerPos1;
    private Location triggerPos2;
    private final List<OutOfBoundsBox> oobBoxes = new ArrayList<>();

    public Checkpoint(String name, Location respawn) {
        this.name = name;
        this.respawn = respawn.clone();
    }

    public String getName()           { return name; }
    public Location getRespawn()      { return respawn.clone(); }

    public Location getTriggerPos1()  { return triggerPos1 != null ? triggerPos1.clone() : null; }
    public Location getTriggerPos2()  { return triggerPos2 != null ? triggerPos2.clone() : null; }
    public boolean hasTrigger()       { return triggerPos1 != null && triggerPos2 != null; }

    public void setTrigger(Location p1, Location p2) {
        this.triggerPos1 = p1.clone();
        this.triggerPos2 = p2.clone();
    }

    public List<OutOfBoundsBox> getOobBoxes() { return oobBoxes; }

    public void addOobBox(OutOfBoundsBox box) { oobBoxes.add(box); }

    /** Removes an OOB box by name. @return true if removed. */
    public boolean removeOobBox(String boxName) {
        return oobBoxes.removeIf(b -> b.getName().equalsIgnoreCase(boxName));
    }

    /** @return the OOB box with this name, or null. */
    public OutOfBoundsBox getOobBox(String boxName) {
        for (OutOfBoundsBox b : oobBoxes) {
            if (b.getName().equalsIgnoreCase(boxName)) return b;
        }
        return null;
    }
}