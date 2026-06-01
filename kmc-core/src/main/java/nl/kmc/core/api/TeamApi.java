package nl.kmc.core.api;

import nl.kmc.core.domain.KMCPlayer;
import nl.kmc.core.domain.KMCTeam;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only team and player lookup surface for game modules. */
public interface TeamApi {

    Optional<KMCTeam> getTeam(String teamId);

    Optional<KMCTeam> getTeamByPlayer(UUID uuid);

    List<KMCTeam> getAllTeams();

    /** Teams ordered by points descending. */
    List<KMCTeam> getStandings();

    Optional<KMCPlayer> getPlayer(UUID uuid);

    /** Returns or creates a KMCPlayer record for the given UUID. */
    KMCPlayer getOrCreatePlayer(UUID uuid, String name);

    /** All players currently tracked (online + cached offline). */
    List<KMCPlayer> getAllPlayers();
}
