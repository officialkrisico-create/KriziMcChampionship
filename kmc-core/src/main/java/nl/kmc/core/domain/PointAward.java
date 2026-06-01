package nl.kmc.core.domain;

import java.util.UUID;

/** Describes a single point award before it is applied. Passed through PointsAwardedEvent. */
public final class PointAward {

    public enum Reason {
        KILL, PLACEMENT, TEAM_PLACEMENT, BONUS, MANUAL, LUCKY_BLOCK,
        DOUBLE_KILL, TRIPLE_KILL, MEGA_KILL, OBJECTIVE, SURVIVAL_BONUS
    }

    private final UUID   playerUuid;
    private final String playerName;
    private final String teamId;
    private final String gameId;
    private final Reason reason;
    private int          amount;   // mutable so event handlers can adjust
    private final int    round;
    private final boolean applyMultiplier;

    public PointAward(UUID playerUuid, String playerName, String teamId,
                      String gameId, Reason reason, int amount, int round,
                      boolean applyMultiplier) {
        this.playerUuid      = playerUuid;
        this.playerName      = playerName;
        this.teamId          = teamId;
        this.gameId          = gameId;
        this.reason          = reason;
        this.amount          = amount;
        this.round           = round;
        this.applyMultiplier = applyMultiplier;
    }

    public UUID   getPlayerUuid()      { return playerUuid; }
    public String getPlayerName()      { return playerName; }
    public String getTeamId()          { return teamId; }
    public String getGameId()          { return gameId; }
    public Reason getReason()          { return reason; }
    public int    getAmount()          { return amount; }
    public int    getRound()           { return round; }
    public boolean isApplyMultiplier() { return applyMultiplier; }

    public void setAmount(int amount)  { this.amount = amount; }

    @Override public String toString() {
        return "PointAward{player=" + playerName + ", amount=" + amount + ", reason=" + reason + "}";
    }
}
