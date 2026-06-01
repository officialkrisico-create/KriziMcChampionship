package nl.kmc.stats.model;

import nl.kmc.core.event.ClutchMomentEvent;

import java.time.Instant;
import java.util.UUID;

/** Persisted record of a detected clutch moment. */
public final class ClutchEvent {

    public UUID                        playerUuid;
    public String                      playerName;
    public String                      gameId;
    public ClutchMomentEvent.ClutchType type;
    public String                      description;
    public int                         round;
    public Instant                     occurredAt;

    public ClutchEvent() { this.occurredAt = Instant.now(); }
}
