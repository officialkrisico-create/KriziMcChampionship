package nl.kmc.tournament.timeline;

import nl.kmc.core.domain.GameRegistration;
import nl.kmc.core.domain.TournamentPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static nl.kmc.tournament.timeline.TournamentTimeline.GameStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TournamentTimelineTest {

    private TournamentTimeline timeline;
    private GameRegistration gameA;
    private GameRegistration gameB;

    @BeforeEach
    void setUp() {
        timeline = new TournamentTimeline(1, 5, TournamentPhase.GAME_ACTIVE);
        gameA = mock(GameRegistration.class);
        gameB = mock(GameRegistration.class);
        when(gameA.getId()).thenReturn("skywars");
        when(gameB.getId()).thenReturn("spleef");
    }

    @Test
    void constructor_sets_fields() {
        assertEquals(1, timeline.getCurrentRound());
        assertEquals(5, timeline.getTotalRounds());
        assertEquals(TournamentPhase.GAME_ACTIVE, timeline.getCurrentPhase());
    }

    @Test
    void starts_with_empty_entries() {
        assertTrue(timeline.getEntries().isEmpty());
    }

    @Test
    void add_entry_stores_entry() {
        var entry = new TournamentTimeline.TimelineEntry(gameA, 1, COMPLETED, "PlayerX", 300);
        timeline.addEntry(entry);
        assertEquals(1, timeline.getEntries().size());
        assertSame(entry, timeline.getEntries().get(0));
    }

    @Test
    void entries_list_is_immutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> timeline.getEntries().add(
                        new TournamentTimeline.TimelineEntry(gameA, 1, UPCOMING, null, 0)));
    }

    @Test
    void get_completed_returns_only_completed() {
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameA, 1, COMPLETED, "MVP", 400));
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameB, 2, UPCOMING, null, 0));

        var completed = timeline.getCompleted();
        assertEquals(1, completed.size());
        assertEquals(COMPLETED, completed.get(0).status());
    }

    @Test
    void get_upcoming_returns_only_upcoming() {
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameA, 1, COMPLETED, "MVP", 400));
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameB, 2, UPCOMING, null, 0));
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameA, 3, UPCOMING, null, 0));

        var upcoming = timeline.getUpcoming();
        assertEquals(2, upcoming.size());
        upcoming.forEach(e -> assertEquals(UPCOMING, e.status()));
    }

    @Test
    void active_entry_excluded_from_completed_and_upcoming() {
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameA, 1, ACTIVE, null, 0));
        assertTrue(timeline.getCompleted().isEmpty());
        assertTrue(timeline.getUpcoming().isEmpty());
    }

    @Test
    void update_changes_round_and_phase() {
        timeline.update(3, 5, TournamentPhase.VOTING_PHASE);
        assertEquals(3, timeline.getCurrentRound());
        assertEquals(TournamentPhase.VOTING_PHASE, timeline.getCurrentPhase());
    }

    @Test
    void update_does_not_clear_entries() {
        timeline.addEntry(new TournamentTimeline.TimelineEntry(gameA, 1, COMPLETED, "x", 100));
        timeline.update(2, 5, TournamentPhase.GAME_ACTIVE);
        assertEquals(1, timeline.getEntries().size());
    }

    @Test
    void timeline_entry_record_stores_fields() {
        var entry = new TournamentTimeline.TimelineEntry(gameA, 2, COMPLETED, "Hero", 500);
        assertSame(gameA, entry.game());
        assertEquals(2, entry.round());
        assertEquals(COMPLETED, entry.status());
        assertEquals("Hero", entry.mvpName());
        assertEquals(500, entry.winnerTeamPoints());
    }
}
