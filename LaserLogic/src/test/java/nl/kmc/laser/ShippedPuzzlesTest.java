package nl.kmc.laser;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The puzzles that ship in config.yml must all be valid: solvable and not solved at the start. */
class ShippedPuzzlesTest {

    @Test
    void everyShippedPuzzleIsValid() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            Map<?, ?> root = new Yaml().load(in);
            List<?> puzzles = (List<?>) root.get("puzzles");
            assertTrue(puzzles.size() >= 6);
            Set<String> ids = new HashSet<>();
            Set<PuzzleDef.Difficulty> seen = new HashSet<>();
            for (Object o : puzzles) {
                List<String> problems = new ArrayList<>();
                PuzzleDef def = PuzzleDef.parse((Map<?, ?>) o, 100, 120, problems);
                assertNotNull(def, problems.toString());
                assertTrue(ids.add(def.id()), "duplicate id " + def.id());
                assertTrue(def.validate().isEmpty(), def.id() + ": " + def.validate());
                seen.add(def.difficulty());
            }
            assertEquals(3, seen.size(), "all three difficulties are covered");
        }
    }
}
