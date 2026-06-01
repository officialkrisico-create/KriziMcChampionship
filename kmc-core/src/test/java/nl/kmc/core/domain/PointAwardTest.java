package nl.kmc.core.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static nl.kmc.core.domain.PointAward.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class PointAwardTest {

    private static PointAward award(PointAward.Reason reason, int amount) {
        return new PointAward(
                UUID.randomUUID(), "TestPlayer", "red",
                "skywars", reason, amount, 2, true
        );
    }

    @Test
    void getters_return_constructor_values() {
        UUID uuid = UUID.randomUUID();
        PointAward a = new PointAward(uuid, "Alice", "blue", "spleef", KILL, 75, 3, false);

        assertEquals(uuid, a.getPlayerUuid());
        assertEquals("Alice", a.getPlayerName());
        assertEquals("blue", a.getTeamId());
        assertEquals("spleef", a.getGameId());
        assertEquals(KILL, a.getReason());
        assertEquals(75, a.getAmount());
        assertEquals(3, a.getRound());
        assertFalse(a.isApplyMultiplier());
    }

    @Test
    void set_amount_mutates_amount() {
        PointAward a = award(BONUS, 50);
        a.setAmount(100);
        assertEquals(100, a.getAmount());
    }

    @Test
    void apply_multiplier_flag_true() {
        PointAward a = award(PLACEMENT, 200);
        assertTrue(a.isApplyMultiplier());
    }

    @Test
    void all_reason_values_are_accessible() {
        PointAward.Reason[] reasons = PointAward.Reason.values();
        assertTrue(reasons.length >= 11);
    }

    @Test
    void to_string_contains_player_amount_and_reason() {
        PointAward a = award(KILL, 75);
        String s = a.toString();
        assertTrue(s.contains("TestPlayer"));
        assertTrue(s.contains("75"));
        assertTrue(s.contains("KILL"));
    }

    @Test
    void lucky_block_reason_is_valid() {
        PointAward a = award(LUCKY_BLOCK, 30);
        assertEquals(LUCKY_BLOCK, a.getReason());
    }

    @Test
    void survival_bonus_reason_is_valid() {
        PointAward a = award(SURVIVAL_BONUS, 5);
        assertEquals(SURVIVAL_BONUS, a.getReason());
    }

    @Test
    void set_amount_to_zero_is_allowed() {
        PointAward a = award(MANUAL, 100);
        a.setAmount(0);
        assertEquals(0, a.getAmount());
    }
}
