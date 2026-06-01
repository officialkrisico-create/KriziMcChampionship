package nl.kmc.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameRegistrationTest {

    @Test
    void builder_stores_id_and_display_name() {
        GameRegistration reg = GameRegistration.builder("skywars", "SkyWars").build();
        assertEquals("skywars", reg.getId());
        assertEquals("SkyWars", reg.getDisplayName());
    }

    @Test
    void builder_defaults_min_players_to_2() {
        GameRegistration reg = GameRegistration.builder("test", "Test").build();
        assertEquals(2, reg.getMinPlayers());
    }

    @Test
    void builder_defaults_max_players_to_100() {
        GameRegistration reg = GameRegistration.builder("test", "Test").build();
        assertEquals(100, reg.getMaxPlayers());
    }

    @Test
    void builder_defaults_description_and_objective_to_empty() {
        GameRegistration reg = GameRegistration.builder("test", "Test").build();
        assertEquals("", reg.getDescription());
        assertEquals("", reg.getObjective());
    }

    @Test
    void builder_respects_custom_min_max_players() {
        GameRegistration reg = GameRegistration.builder("sg", "Survival Games")
                .minPlayers(4).maxPlayers(16).build();
        assertEquals(4, reg.getMinPlayers());
        assertEquals(16, reg.getMaxPlayers());
    }

    @Test
    void builder_respects_description_and_objective() {
        GameRegistration reg = GameRegistration.builder("bingo", "Bingo")
                .description("Team bingo card game.")
                .objective("Complete a line first.")
                .build();
        assertEquals("Team bingo card game.", reg.getDescription());
        assertEquals("Complete a line first.", reg.getObjective());
    }

    @Test
    void to_string_contains_id() {
        GameRegistration reg = GameRegistration.builder("quake_craft", "QuakeCraft").build();
        assertTrue(reg.toString().contains("quake_craft"));
    }

    @Test
    void build_twice_produces_independent_instances() {
        var builder = GameRegistration.builder("a", "A");
        GameRegistration r1 = builder.build();
        GameRegistration r2 = builder.build();
        assertNotSame(r1, r2);
        assertEquals(r1.getId(), r2.getId());
    }
}
