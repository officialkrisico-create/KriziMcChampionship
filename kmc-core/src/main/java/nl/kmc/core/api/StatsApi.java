package nl.kmc.core.api;

import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.domain.KMCTeam;

import java.util.List;
import java.util.UUID;

/** Statistics and leaderboard queries for game modules and display systems. */
public interface StatsApi {

    /** Top N players by tournament points. */
    List<KMCPlayer> getPlayerLeaderboard(int limit);

    /** Top N teams by tournament points. */
    List<KMCTeam> getTeamLeaderboard(int limit);

    /** Total kills by player this tournament. */
    int getKills(UUID uuid);

    /** Total deaths by player this tournament. */
    int getDeaths(UUID uuid);

    int getGamesPlayed(UUID uuid);

    /** Total tournament points for a player. */
    int getPoints(UUID uuid);

    int getTeamPoints(String teamId);

    /** Player's 1-based rank by points. */
    int getPlayerRank(UUID uuid);

    /** Team's 1-based rank by points. */
    int getTeamRank(String teamId);
}
