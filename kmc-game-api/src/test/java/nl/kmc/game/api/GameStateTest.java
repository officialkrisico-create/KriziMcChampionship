package nl.kmc.game.api;

import org.junit.jupiter.api.Test;

import static nl.kmc.game.api.GameState.*;
import static org.junit.jupiter.api.Assertions.*;

class GameStateTest {

    @Test
    void idle_is_idle() {
        assertTrue(IDLE.isIdle());
        assertFalse(ACTIVE.isIdle());
    }

    @Test
    void ended_is_over() {
        assertTrue(ENDED.isOver());
        assertFalse(IDLE.isOver());
    }

    @Test
    void running_states_are_correct() {
        assertTrue(COUNTDOWN.isRunning());
        assertTrue(GRACE.isRunning());
        assertTrue(ACTIVE.isRunning());
        assertTrue(DEATHMATCH.isRunning());
        assertFalse(IDLE.isRunning());
        assertFalse(PREPARING.isRunning());
        assertFalse(ENDED.isRunning());
    }

    @Test
    void pvp_active_only_in_active_and_deathmatch() {
        assertTrue(ACTIVE.isPvPActive());
        assertTrue(DEATHMATCH.isPvPActive());
        assertFalse(IDLE.isPvPActive());
        assertFalse(GRACE.isPvPActive());
        assertFalse(COUNTDOWN.isPvPActive());
    }
}
