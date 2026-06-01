package nl.kmc.storage.model;

import java.util.UUID;

/** Per-player snapshot captured at tournament end. */
public final class StoredPlayerResult {

    public int eventNumber;
    public UUID playerUuid;
    public String playerName;
    public String teamId;
    public int finalPoints;
    public int finalKills;
    public int finalDeaths;
    public int finalWins;
    public int placement;        // 1-based rank among all players
    public boolean wonTournament;

    public StoredPlayerResult() {}
}
