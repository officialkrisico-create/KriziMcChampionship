package nl.kmc.mayhem.waves;

import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.List;

/**
 * Describes a single wave in the Mob Mayhem progression.
 *
 * <p>A wave consists of one or more mob spawn entries. Each entry says
 * "spawn N of EntityType X". The wave is "complete" when all spawned
 * mobs are dead (or the wave timer expires, configurable).
 *
 * <p>Boss waves use the same structure but with one entry of count 1
 * for a beefed-up entity. Plugin tags spawned mobs with their wave
 * number so we can clean them up if the game ends abruptly.
 */
public class WaveDefinition {

    public record SpawnEntry(EntityType type, int count) {}

    private final int             waveNumber;
    private final String          displayName;
    private final List<SpawnEntry> spawns;
    private final boolean         isBossWave;
    private final int             durationSeconds;

    public WaveDefinition(int waveNumber, String displayName,
                          List<SpawnEntry> spawns, boolean isBossWave,
                          int durationSeconds) {
        this.waveNumber       = waveNumber;
        this.displayName      = displayName;
        this.spawns           = List.copyOf(spawns);
        this.isBossWave       = isBossWave;
        this.durationSeconds  = durationSeconds;
    }

    public int             getWaveNumber()      { return waveNumber; }
    public String          getDisplayName()     { return displayName; }
    public List<SpawnEntry> getSpawns()         { return Collections.unmodifiableList(spawns); }
    public boolean         isBossWave()         { return isBossWave; }
    public int             getDurationSeconds() { return durationSeconds; }

    /** Total number of mobs that will spawn in this wave. */
    public int getTotalMobCount() {
        int total = 0;
        for (SpawnEntry e : spawns) total += e.count();
        return total;
    }
}
