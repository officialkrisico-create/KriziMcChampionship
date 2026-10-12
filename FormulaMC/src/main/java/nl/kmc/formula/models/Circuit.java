package nl.kmc.formula.models;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One race track: grid, start/finish line, ordered checkpoints, number of laps and a safe reset spot. */
public final class Circuit {

    private final String id;
    private String displayName;
    private World world;
    private int laps = 3;
    private final List<Location> spawns = new ArrayList<>();
    private Gate line;
    private final List<Gate> checkpoints = new ArrayList<>();
    private final List<Gate> boostPads = new ArrayList<>();
    private final List<Location> itemBoxes = new ArrayList<>();
    private Location reset;

    public Circuit(String id, String displayName) {
        this.id = id;
        this.displayName = displayName != null ? displayName : id;
    }

    public String getId()                { return id; }
    public String getDisplayName()       { return displayName; }
    public void   setDisplayName(String n) { this.displayName = n; }
    public World  getWorld()             { return world; }
    public void   setWorld(World w)      { this.world = w; }
    public int    getLaps()              { return laps; }
    public void   setLaps(int laps)      { this.laps = Math.max(1, laps); }
    public List<Location> getSpawns()    { return spawns; }
    public Gate   getLine()              { return line; }
    public void   setLine(Gate g)        { this.line = g; }
    public List<Gate> getCheckpoints()   { return checkpoints; }
    /** Areas that give a speed boost to a boat driving over them. */
    public List<Gate> getBoostPads()     { return boostPads; }
    /** Spots where a power-up item box floats. */
    public List<Location> getItemBoxes() { return itemBoxes; }
    public Location getReset()           { return reset != null ? reset.clone() : null; }
    public void   setReset(Location l)   { this.reset = l != null ? l.clone() : null; }

    /** Problems that stop this circuit from being raced on (empty = ready). */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (world == null) out.add("geen wereld (voer /fmc create uit in de juiste wereld)");
        if (spawns.isEmpty()) out.add("geen startposities (/fmc spawn add)");
        if (line == null) out.add("geen start/finish-lijn (/fmc pos1, /fmc pos2, /fmc line)");
        return out;
    }

    public boolean isReady() { return problems().isEmpty(); }

    public List<Location> spawnsView() { return Collections.unmodifiableList(spawns); }
}
