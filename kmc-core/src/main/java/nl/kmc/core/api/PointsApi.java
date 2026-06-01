package nl.kmc.core.api;

import nl.kmc.core.domain.PointAward;

import java.util.UUID;

/** Points and placement award surface for game modules. */
public interface PointsApi {

    /**
     * Awards points to a player (and propagates to their team).
     * Fires {@link nl.kmc.core.event.PointsAwardedEvent} — cancelling it suppresses the award.
     *
     * @param uuid   target player
     * @param amount base points (before multiplier)
     * @param reason human-readable reason for audit trail
     * @param gameId the awarding game's id
     */
    void givePoints(UUID uuid, int amount, PointAward.Reason reason, String gameId);

    /** Awards points directly to a team without attributing to a specific player. */
    void giveTeamPoints(String teamId, int amount, PointAward.Reason reason, String gameId);

    /**
     * Awards placement-based points from the points.yml curve.
     *
     * @param uuid      player
     * @param placement 1-based rank
     * @param totalPlayers total participants (used for relative bonus calc)
     * @param gameId    awarding game
     */
    void awardPlayerPlacement(UUID uuid, int placement, int totalPlayers, String gameId);

    void awardTeamPlacement(String teamId, int placement, String gameId);

    /** Returns the active round multiplier. */
    double getCurrentMultiplier();

    /** Admin: directly set a player's points. */
    void setPoints(UUID uuid, int points);

    /** Admin: directly set a team's points. */
    void setTeamPoints(String teamId, int points);

    /** Admin: adjust points by delta (can be negative). */
    void adjustPoints(UUID uuid, int delta, String reason);

    void adjustTeamPoints(String teamId, int delta, String reason);
}
