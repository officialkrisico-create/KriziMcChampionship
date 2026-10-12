package nl.kmc.kmccore.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamJinglesTest {

    @Test
    void defaultTuneIsDeterministicPerTeam() {
        TeamJingles.Tune a = TeamJingles.defaultTune("red");
        TeamJingles.Tune b = TeamJingles.defaultTune("RED");
        assertEquals(a.instrument(), b.instrument());
        assertArrayEquals(a.notes(), b.notes());
    }

    @Test
    void defaultTuneStaysInNoteBlockRange() {
        for (String id : new String[] {"red", "blue", "geel", "groen", "x", "", "a-very-long-team-identifier"}) {
            TeamJingles.Tune t = TeamJingles.defaultTune(id);
            assertNotNull(t.instrument());
            assertTrue(t.notes().length > 0);
            assertTrue(t.speed() > 0);
            for (int n : t.notes()) assertTrue(n >= 0 && n <= 24, "note " + n + " for " + id);
        }
    }

    @Test
    void nullTeamIdDoesNotCrash() {
        assertNotNull(TeamJingles.defaultTune(null));
    }
}
