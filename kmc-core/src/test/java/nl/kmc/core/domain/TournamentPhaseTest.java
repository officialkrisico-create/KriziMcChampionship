package nl.kmc.core.domain;

import org.junit.jupiter.api.Test;

import static nl.kmc.core.domain.TournamentPhase.*;
import static org.junit.jupiter.api.Assertions.*;

class TournamentPhaseTest {

    @Test
    void game_active_is_game_running() {
        assertTrue(GAME_ACTIVE.isGameRunning());
        assertFalse(VOTING_PHASE.isGameRunning());
    }

    @Test
    void voting_phase_is_voting() {
        assertTrue(VOTING_PHASE.isVoting());
        assertFalse(GAME_ACTIVE.isVoting());
    }

    @Test
    void ended_is_over() {
        assertTrue(ENDED.isOver());
        assertFalse(GAME_ACTIVE.isOver());
    }

    @Test
    void pre_game_phases_identified() {
        assertTrue(WAITING.isPreGame());
        assertTrue(READY_CHECK.isPreGame());
        assertTrue(OPENING_CEREMONY.isPreGame());
        assertTrue(TEAM_SHOWCASE.isPreGame());
        assertTrue(TOURNAMENT_OVERVIEW.isPreGame());
        assertTrue(GAME_LINEUP_SHOWCASE.isPreGame());
        assertFalse(GAME_ACTIVE.isPreGame());
        assertFalse(VOTING_PHASE.isPreGame());
    }

    @Test
    void ceremony_phases_identified() {
        assertTrue(OPENING_CEREMONY.isCeremony());
        assertTrue(GAME_INTRO.isCeremony());
        assertTrue(GAME_END_CEREMONY.isCeremony());
        assertTrue(ROUND_END_CEREMONY.isCeremony());
        assertTrue(CLOSING_CEREMONY.isCeremony());
        assertFalse(GAME_ACTIVE.isCeremony());
        assertFalse(WAITING.isCeremony());
    }
}
