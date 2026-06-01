package nl.kmc.tournament.template;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TournamentTemplateTest {

    @Test
    void builder_defaults_are_sensible() {
        TournamentTemplate t = TournamentTemplate.builder("kmc5", "KMC Season 5").build();

        assertEquals("kmc5", t.getId());
        assertEquals("KMC Season 5", t.getDisplayName());
        assertEquals(5, t.getTotalRounds());
        assertTrue(t.isVotingEnabled());
        assertEquals(30, t.getVotingDurationSeconds());
        assertNotNull(t.getCreatedAt());
        assertNotNull(t.getUpdatedAt());
        assertNotNull(t.getGameRotation());
        assertNotNull(t.getRoundMultipliers());
    }

    @Test
    void builder_respects_custom_values() {
        List<String> rotation = List.of("skywars", "spleef", "tnttag");
        Map<Integer, Double> mult = Map.of(1, 1.0, 2, 2.0, 3, 3.0);

        TournamentTemplate t = TournamentTemplate.builder("custom", "Custom Format")
                .totalRounds(3)
                .gameRotation(rotation)
                .roundMultipliers(mult)
                .votingEnabled(false)
                .votingDurationSeconds(60)
                .description("Three-round lightning format")
                .build();

        assertEquals(3, t.getTotalRounds());
        assertEquals(rotation, t.getGameRotation());
        assertEquals(mult, t.getRoundMultipliers());
        assertFalse(t.isVotingEnabled());
        assertEquals(60, t.getVotingDurationSeconds());
        assertEquals("Three-round lightning format", t.getDescription());
    }

    @Test
    void game_rotation_is_immutable_after_build() {
        TournamentTemplate t = TournamentTemplate.builder("t", "T")
                .gameRotation(List.of("a", "b"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> t.getGameRotation().add("c"));
    }

    @Test
    void round_multipliers_are_immutable_after_build() {
        TournamentTemplate t = TournamentTemplate.builder("t", "T").build();
        assertThrows(UnsupportedOperationException.class,
                () -> t.getRoundMultipliers().put(99, 10.0));
    }

    @Test
    void created_at_is_not_in_the_future() {
        TournamentTemplate t = TournamentTemplate.builder("t", "T").build();
        assertFalse(t.getCreatedAt().isAfter(Instant.now().plusSeconds(1)));
    }

    @Test
    void build_twice_produces_different_instances() {
        var builder = TournamentTemplate.builder("id", "Name");
        TournamentTemplate t1 = builder.build();
        TournamentTemplate t2 = builder.build();
        assertNotSame(t1, t2);
        assertEquals(t1.getId(), t2.getId());
    }

    @Test
    void to_string_contains_id_and_rounds() {
        TournamentTemplate t = TournamentTemplate.builder("kmc", "KMC").totalRounds(7).build();
        String s = t.toString();
        assertTrue(s.contains("kmc"));
        assertTrue(s.contains("7"));
    }
}
