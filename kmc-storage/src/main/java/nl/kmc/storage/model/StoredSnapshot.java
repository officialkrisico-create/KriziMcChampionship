package nl.kmc.storage.model;

import java.time.Instant;

/**
 * A serialized tournament checkpoint. The {@code payload} is a JSON string
 * encoding the full {@code RecoverySnapshot} domain object. Keeping the blob
 * opaque here isolates the storage layer from domain changes.
 */
public final class StoredSnapshot {

    public String label;
    public String payload;   // JSON-encoded RecoverySnapshot
    public Instant createdAt;
    public String phase;     // TournamentPhase name at capture time

    public StoredSnapshot() {}

    public StoredSnapshot(String label, String payload, String phase) {
        this.label = label;
        this.payload = payload;
        this.phase = phase;
        this.createdAt = Instant.now();
    }
}
