package nl.kmc.tournament.voting;

import nl.kmc.core.domain.GameRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoteResultProcessorTest {

    private static GameRegistration reg(String id) {
        GameRegistration r = mock(GameRegistration.class);
        when(r.getId()).thenReturn(id);
        return r;
    }

    @Test
    void empty_when_no_candidates() {
        VoteSession empty = new VoteSession(List.of());
        Optional<GameRegistration> result = VoteResultProcessor.resolve(empty);
        assertTrue(result.isEmpty());
    }

    @Test
    void single_candidate_wins_even_with_zero_votes() {
        GameRegistration only = reg("parkour_warrior");
        VoteSession session = new VoteSession(List.of(only));
        var result = VoteResultProcessor.resolve(session);
        assertTrue(result.isPresent());
        assertEquals("parkour_warrior", result.get().getId());
    }

    @Test
    void clear_winner_with_most_votes() {
        GameRegistration a = reg("skywars");
        GameRegistration b = reg("spleef");
        VoteSession session = new VoteSession(List.of(a, b));
        session.castVote(UUID.randomUUID(), "skywars");
        session.castVote(UUID.randomUUID(), "skywars");
        session.castVote(UUID.randomUUID(), "spleef");

        var result = VoteResultProcessor.resolve(session);
        assertTrue(result.isPresent());
        assertEquals("skywars", result.get().getId());
    }

    @RepeatedTest(20)
    void tied_vote_returns_one_of_the_tied_candidates() {
        GameRegistration a = reg("skywars");
        GameRegistration b = reg("spleef");
        VoteSession session = new VoteSession(List.of(a, b));
        session.castVote(UUID.randomUUID(), "skywars");
        session.castVote(UUID.randomUUID(), "spleef");

        var result = VoteResultProcessor.resolve(session);
        assertTrue(result.isPresent());
        assertTrue(result.get().getId().equals("skywars") || result.get().getId().equals("spleef"),
                "Tied vote must resolve to one of the tied candidates");
    }

    @Test
    void all_zero_votes_returns_random_candidate() {
        GameRegistration a = reg("a");
        GameRegistration b = reg("b");
        GameRegistration c = reg("c");
        VoteSession session = new VoteSession(List.of(a, b, c));

        var result = VoteResultProcessor.resolve(session);
        assertTrue(result.isPresent());
    }

    @Test
    void result_is_non_null_when_one_player_voted() {
        GameRegistration a = reg("tnttag");
        GameRegistration b = reg("bingo");
        VoteSession session = new VoteSession(List.of(a, b));
        session.castVote(UUID.randomUUID(), "tnttag");

        var result = VoteResultProcessor.resolve(session);
        assertTrue(result.isPresent());
        assertEquals("tnttag", result.get().getId());
    }

    @Test
    void closed_session_can_still_be_resolved() {
        GameRegistration a = reg("quake_craft");
        VoteSession session = new VoteSession(List.of(a));
        session.close();
        var result = VoteResultProcessor.resolve(session);
        assertTrue(result.isPresent());
    }
}
