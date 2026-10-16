package nl.kmc.kmccore.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostStateMachineTest {

    @Test
    void theHostIsSilentUntilASessionStarts() {
        HostStateMachine sm = new HostStateMachine();
        assertEquals(HostState.IDLE, sm.state());
        assertFalse(sm.canSpeakAutomatically(), "placing the NPC or a join must never start a speech");
        assertTrue(sm.startSession());
        assertEquals(HostState.ACTIVE, sm.state());
        assertTrue(sm.canSpeakAutomatically());
    }

    @Test
    void startingTwiceIsIdempotent() {
        HostStateMachine sm = new HostStateMachine();
        assertTrue(sm.startSession());
        int gen = sm.generation();
        assertFalse(sm.startSession(), "a duplicate callback of the same start must do nothing");
        assertEquals(gen, sm.generation());
    }

    @Test
    void stoppingReturnsToIdleAndInvalidatesScheduledWork() {
        HostStateMachine sm = new HostStateMachine();
        sm.startSession();
        int gen = sm.generation();
        assertTrue(sm.stopSession());
        assertEquals(HostState.IDLE, sm.state());
        assertNotEquals(gen, sm.generation());
        assertFalse(sm.stopSession(), "stopping an idle host changes nothing");
        assertTrue(sm.startSession(), "a new /kmcauto start works again");
    }

    @Test
    void theNaturalEndOfTheTournamentPassesThroughFinishing() {
        HostStateMachine sm = new HostStateMachine();
        sm.startSession();
        sm.finishOnNextStop();
        assertTrue(sm.stopSession());
        assertEquals(HostState.FINISHING, sm.state());
        assertTrue(sm.canSpeakAutomatically(), "the podium and the awards are presented while FINISHING");
        assertTrue(sm.endFinishing());
        assertEquals(HostState.IDLE, sm.state());
        assertFalse(sm.canSpeakAutomatically());
    }

    @Test
    void anAdminStopDuringTheSessionGoesStraightToIdle() {
        HostStateMachine sm = new HostStateMachine();
        sm.startSession();
        sm.stopSession();
        assertEquals(HostState.IDLE, sm.state());
        sm.startSession();
        sm.finishOnNextStop();
        sm.stopSession();
        assertEquals(HostState.FINISHING, sm.state());
        sm.stopSession();                      // /kmcauto stop while the end sequence plays
        assertEquals(HostState.IDLE, sm.state());
    }

    @Test
    void finishingCannotBeRequestedWithoutASession() {
        HostStateMachine sm = new HostStateMachine();
        sm.finishOnNextStop();
        sm.stopSession();
        assertEquals(HostState.IDLE, sm.state());
    }

    @Test
    void aDisabledHostNeverStartsOrSpeaks() {
        HostStateMachine sm = new HostStateMachine();
        assertTrue(sm.setEnabled(false));
        assertEquals(HostState.DISABLED, sm.state());
        assertFalse(sm.startSession());
        assertFalse(sm.canSpeakAutomatically());
        assertTrue(sm.setEnabled(true));
        assertEquals(HostState.IDLE, sm.state());
    }
}
