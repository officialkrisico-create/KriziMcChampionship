package nl.kmc.kmccore.models;

import java.util.UUID;

/**
 * A single point-award event — used for the per-player and per-team
 * "where did my points come from" breakdown in the post-event book.
 *
 * <p>Stored in the {@code point_awards} table, all rows for the current
 * tournament. Cleared when the tournament resets / ends.
 *
 * <p>Reasons are short strings like:
 *   "kill", "placement_1", "lucky_block_loot", "team_placement_1"
 */
public class PointAward {

    private final UUID    playerUuid;
    private final String  teamId;       // may be null (player had no team at award time)
    private final String  reason;
    private final String  gameId;       // may be null (admin command awards etc.)
    private final int     amount;
    private final int     round;
    private final long    timestamp;    // millis

    public PointAward(UUID playerUuid, String teamId, String reason, String gameId,
                      int amount, int round, long timestamp) {
        this.playerUuid = playerUuid;
        this.teamId     = teamId;
        this.reason     = reason;
        this.gameId     = gameId;
        this.amount     = amount;
        this.round      = round;
        this.timestamp  = timestamp;
    }

    public UUID    getPlayerUuid() { return playerUuid; }
    public String  getTeamId()     { return teamId; }
    public String  getReason()     { return reason; }
    public String  getGameId()     { return gameId; }
    public int     getAmount()     { return amount; }
    public int     getRound()      { return round; }
    public long    getTimestamp()  { return timestamp; }

    /** Friendly display string for the reason, e.g. "Kill", "Placement (1st)". */
    public String getDisplayReason() {
        if (reason == null) return "Onbekend";
        return switch (reason) {
            case "kill"            -> "Kill";
            case "lucky_block_loot"-> "Lucky Block";
            case "double_kill"     -> "Double Kill";
            case "triple_kill"     -> "Triple Kill";
            case "mega_kill"       -> "Mega Kill";
            case "admin_award"     -> "Admin";
            default -> {
                if (reason.startsWith("placement_"))
                    yield "Plaats " + reason.substring("placement_".length());
                if (reason.startsWith("team_placement_"))
                    yield "Team Plaats " + reason.substring("team_placement_".length());
                yield reason;
            }
        };
    }
}
