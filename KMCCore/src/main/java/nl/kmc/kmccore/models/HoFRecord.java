package nl.kmc.kmccore.models;

import java.util.UUID;

/**
 * A Hall of Fame record. Survives all soft and hard resets;
 * only cleared via /kmchof clear.
 *
 * <p>Each row in the {@code hof_records} table represents the current
 * record holder for one category. There is at most one row per category.
 *
 * <p>For "lifetime total" categories (e.g. most-points-all-time), the
 * holder may change as the same player accumulates more points over
 * multiple events. For "single event max" categories (e.g. most-kills-in-a-game),
 * the value is set once and only overwritten if someone beats it in a
 * future event.
 */
public class HoFRecord {

    private final String category;       // e.g. "most_kills_game"
    private UUID         playerUuid;
    private String       playerName;     // cached for offline display
    private long         value;
    private int          eventNumber;    // event during which the record was set
    private long         timestamp;      // millis when the record was set

    public HoFRecord(String category, UUID playerUuid, String playerName,
                     long value, int eventNumber, long timestamp) {
        this.category    = category;
        this.playerUuid  = playerUuid;
        this.playerName  = playerName;
        this.value       = value;
        this.eventNumber = eventNumber;
        this.timestamp   = timestamp;
    }

    public String getCategory()      { return category; }
    public UUID   getPlayerUuid()    { return playerUuid; }
    public String getPlayerName()    { return playerName; }
    public long   getValue()         { return value; }
    public int    getEventNumber()   { return eventNumber; }
    public long   getTimestamp()     { return timestamp; }

    public void update(UUID playerUuid, String playerName, long value, int eventNumber) {
        this.playerUuid  = playerUuid;
        this.playerName  = playerName;
        this.value       = value;
        this.eventNumber = eventNumber;
        this.timestamp   = System.currentTimeMillis();
    }
}
