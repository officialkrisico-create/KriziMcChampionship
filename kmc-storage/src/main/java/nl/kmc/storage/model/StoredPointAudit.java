package nl.kmc.storage.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit record for a single point award. */
public final class StoredPointAudit {

    public UUID playerUuid;
    public String playerName;
    public String teamId;
    public String gameId;
    public String reason;
    public int amount;
    public int round;
    public Instant timestamp;

    public StoredPointAudit() {}

    public StoredPointAudit(UUID playerUuid, String playerName, String teamId,
                            String gameId, String reason, int amount, int round) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.teamId = teamId;
        this.gameId = gameId;
        this.reason = reason;
        this.amount = amount;
        this.round = round;
        this.timestamp = Instant.now();
    }
}
