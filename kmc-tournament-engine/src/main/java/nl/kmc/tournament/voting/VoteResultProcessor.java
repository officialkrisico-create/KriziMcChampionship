package nl.kmc.tournament.voting;

import nl.kmc.core.domain.GameRegistration;

import java.util.*;

/**
 * Converts a finished {@link VoteSession} into an Optional winner.
 * Tie-breaking: random selection among tied candidates, preserving fairness.
 */
public final class VoteResultProcessor {

    private static final Random RNG = new Random();

    private VoteResultProcessor() {}

    /**
     * Determines the winning game from the vote tally.
     * Returns empty if no candidates exist.
     */
    public static Optional<GameRegistration> resolve(VoteSession session) {
        Map<String, Integer> tally = session.tally();
        List<GameRegistration> candidates = session.getCandidates();

        if (candidates.isEmpty()) return Optional.empty();

        int max = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<GameRegistration> tied = candidates.stream()
                .filter(r -> tally.getOrDefault(r.getId().toLowerCase(Locale.ROOT), 0) == max)
                .toList();

        return Optional.of(tied.get(RNG.nextInt(tied.size())));
    }
}
