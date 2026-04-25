package nl.kmc.mayhem.models;

import nl.kmc.mayhem.waves.WaveModifier;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-team game state during an active Mob Mayhem match.
 *
 * <p>Each KMCCore team plays in their own arena. This object tracks
 * their progress: which wave they're on, who's still alive, total
 * mob kills + points earned, and the active modifiers for the
 * current wave.
 */
public class TeamGameState {

    private final String  teamId;
    private final String  arenaId;

    /** Players who started the match for this team. */
    private final Set<UUID> allPlayers = new HashSet<>();

    /** Players still alive (not eliminated). */
    private final Set<UUID> alivePlayers = new HashSet<>();

    /** Set of currently-spawned mob entity UUIDs for this team's wave. */
    private final Set<UUID> activeMobs = new HashSet<>();

    private int          currentWave;
    private int          mobsKilled;
    private int          totalPoints;
    private int          highestWaveSurvived;
    private long         waveStartMs;
    private boolean      eliminated;

    /** Modifiers active on the current wave. */
    private final Set<WaveModifier> activeModifiers = new HashSet<>();

    public TeamGameState(String teamId, String arenaId) {
        this.teamId  = teamId;
        this.arenaId = arenaId;
    }

    public void addPlayer(UUID uuid) {
        allPlayers.add(uuid);
        alivePlayers.add(uuid);
    }

    public void eliminatePlayer(UUID uuid) {
        alivePlayers.remove(uuid);
        if (alivePlayers.isEmpty()) {
            this.eliminated = true;
        }
    }

    public void revivePlayer(UUID uuid) {
        if (allPlayers.contains(uuid)) alivePlayers.add(uuid);
    }

    // ---- Wave control --------------------------------------------

    public void startWave(int waveNumber, Set<WaveModifier> modifiers) {
        this.currentWave         = waveNumber;
        this.activeModifiers.clear();
        this.activeModifiers.addAll(modifiers);
        this.activeMobs.clear();
        this.waveStartMs = System.currentTimeMillis();
    }

    public void completeWave() {
        if (currentWave > highestWaveSurvived) highestWaveSurvived = currentWave;
        activeMobs.clear();
        activeModifiers.clear();
    }

    // ---- Mob tracking --------------------------------------------

    public void addMob(UUID mobUuid)    { activeMobs.add(mobUuid); }
    public boolean removeMob(UUID uuid) { return activeMobs.remove(uuid); }
    public int     getActiveMobCount()  { return activeMobs.size(); }

    public void recordKill(int pointValue) {
        mobsKilled++;
        totalPoints += pointValue;
    }

    // ---- Getters --------------------------------------------------

    public String  getTeamId()              { return teamId; }
    public String  getArenaId()             { return arenaId; }
    public Set<UUID> getAllPlayers()        { return allPlayers; }
    public Set<UUID> getAlivePlayers()      { return alivePlayers; }
    public Set<UUID> getActiveMobs()        { return activeMobs; }
    public int     getCurrentWave()         { return currentWave; }
    public int     getMobsKilled()          { return mobsKilled; }
    public int     getTotalPoints()         { return totalPoints; }
    public int     getHighestWaveSurvived() { return highestWaveSurvived; }
    public long    getWaveStartMs()         { return waveStartMs; }
    public boolean isEliminated()           { return eliminated; }
    public Set<WaveModifier> getActiveModifiers() { return activeModifiers; }

    public long getWaveElapsedMs() {
        return waveStartMs > 0 ? System.currentTimeMillis() - waveStartMs : 0;
    }
}
