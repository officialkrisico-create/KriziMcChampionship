package nl.kmc.stats.model;

import java.time.Instant;
import java.util.UUID;

/** Per-player stats snapshot captured at the end of a single game. */
public final class GameStats {

    public UUID    playerUuid;
    public String  playerName;
    public String  teamId;
    public String  gameId;
    public int     kills;
    public int     deaths;
    public int     assists;
    public int     damageDealt;
    public int     damageTaken;
    public int     objectivesCompleted;
    public int     pointsEarned;
    public int     placement;           // 1-based final rank
    public boolean won;
    public long    survivalSeconds;
    public int     round;
    public Instant recordedAt;

    public GameStats() { this.recordedAt = Instant.now(); }
}
