package nl.kmc.storage.model;

import java.time.Instant;

/** Summary record written at tournament end before stats are reset. */
public final class StoredTournamentResult {

    public int eventNumber;
    public String tournamentName;
    public String winningTeamId;
    public String winningTeamName;
    public int totalRounds;
    public Instant startedAt;
    public Instant endedAt;

    public StoredTournamentResult() {}
}
