package nl.kmc.tournament.voting;

import nl.kmc.core.domain.GameRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoteSessionTest {

    private GameRegistration regA;
    private GameRegistration regB;
    private VoteSession session;

    @BeforeEach
    void setUp() {
        regA = mock(GameRegistration.class);
        regB = mock(GameRegistration.class);
        when(regA.getId()).thenReturn("skywars");
        when(regB.getId()).thenReturn("spleef");
        session = new VoteSession(List.of(regA, regB));
    }

    @Test
    void new_session_is_active() {
        assertTrue(session.isActive());
    }

    @Test
    void cast_vote_returns_true_for_valid_game() {
        assertTrue(session.castVote(UUID.randomUUID(), "skywars"));
    }

    @Test
    void cast_vote_returns_false_for_unknown_game() {
        assertFalse(session.castVote(UUID.randomUUID(), "quakecraft"));
    }

    @Test
    void cast_vote_is_case_insensitive() {
        assertTrue(session.castVote(UUID.randomUUID(), "SkyWars"));
        assertTrue(session.castVote(UUID.randomUUID(), "SPLEEF"));
    }

    @Test
    void vote_is_stored_after_cast() {
        UUID voter = UUID.randomUUID();
        session.castVote(voter, "skywars");
        assertEquals("skywars", session.getVotes().get(voter));
    }

    @Test
    void later_vote_overwrites_earlier_vote() {
        UUID voter = UUID.randomUUID();
        session.castVote(voter, "skywars");
        session.castVote(voter, "spleef");
        assertEquals("spleef", session.getVotes().get(voter));
    }

    @Test
    void cast_vote_fails_after_session_closed() {
        session.close();
        assertFalse(session.castVote(UUID.randomUUID(), "skywars"));
        assertFalse(session.isActive());
    }

    @Test
    void tally_starts_at_zero_for_all_candidates() {
        var tally = session.tally();
        assertEquals(0, tally.get("skywars"));
        assertEquals(0, tally.get("spleef"));
    }

    @Test
    void tally_counts_votes_correctly() {
        session.castVote(UUID.randomUUID(), "skywars");
        session.castVote(UUID.randomUUID(), "skywars");
        session.castVote(UUID.randomUUID(), "spleef");

        var tally = session.tally();
        assertEquals(2, tally.get("skywars"));
        assertEquals(1, tally.get("spleef"));
    }

    @Test
    void tally_overwritten_vote_not_double_counted() {
        UUID voter = UUID.randomUUID();
        session.castVote(voter, "skywars");
        session.castVote(voter, "spleef"); // overwrite

        var tally = session.tally();
        assertEquals(0, tally.get("skywars"));
        assertEquals(1, tally.get("spleef"));
    }

    @Test
    void candidates_list_is_immutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> session.getCandidates().add(mock(GameRegistration.class)));
    }

    @Test
    void votes_map_is_immutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> session.getVotes().put(UUID.randomUUID(), "skywars"));
    }
}
