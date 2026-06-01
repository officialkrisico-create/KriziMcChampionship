package nl.kmc.tournament.voting;

import nl.kmc.core.domain.GameRegistration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the state of a single vote: which games are on offer and who voted for what.
 */
public final class VoteSession {

    private final List<GameRegistration> candidates;
    private final Map<UUID, String>      votes      = new ConcurrentHashMap<>();
    private volatile boolean             active     = true;

    public VoteSession(List<GameRegistration> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    /**
     * Cast or overwrite a vote. Returns false if the session is no longer active
     * or the gameId is not a valid candidate.
     */
    public boolean castVote(UUID voter, String gameId) {
        if (!active) return false;
        boolean valid = candidates.stream().anyMatch(r -> r.getId().equalsIgnoreCase(gameId));
        if (!valid) return false;
        votes.put(voter, gameId.toLowerCase(java.util.Locale.ROOT));
        return true;
    }

    public void close() { active = false; }

    public boolean isActive() { return active; }

    public List<GameRegistration> getCandidates() { return candidates; }

    /** Unmodifiable snapshot of all votes. */
    public Map<UUID, String> getVotes() { return Collections.unmodifiableMap(votes); }

    /** How many players voted for each game. */
    public Map<String, Integer> tally() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        candidates.forEach(r -> counts.put(r.getId().toLowerCase(java.util.Locale.ROOT), 0));
        votes.values().forEach(id -> counts.merge(id, 1, Integer::sum));
        return counts;
    }
}
