package nl.kmc.storage.model;

import java.util.ArrayList;
import java.util.List;

/** Flat persistence record for the active tournament session. */
public final class StoredTournamentState {

    public boolean active;
    public int currentRound;
    public int eventNumber;
    public String activeGameId;          // null when no game is running
    public String tournamentPhase;       // TournamentPhase enum name
    public List<String> playedGameIds = new ArrayList<>();

    public StoredTournamentState() {}
}
