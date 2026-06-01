package nl.kmc.stats.model;

import java.util.UUID;

/** Result of an MVP calculation for a single game or entire tournament. */
public final class MVPResult {

    public enum Scope { GAME, TOURNAMENT }

    public UUID   playerUuid;
    public String playerName;
    public String gameId;        // null for TOURNAMENT scope
    public Scope  scope;
    public double score;         // weighted performance score
    public int    kills;
    public int    pointsEarned;
    public boolean won;

    public MVPResult() {}

    public MVPResult(UUID playerUuid, String playerName, String gameId,
                     Scope scope, double score) {
        this.playerUuid  = playerUuid;
        this.playerName  = playerName;
        this.gameId      = gameId;
        this.scope       = scope;
        this.score       = score;
    }
}
