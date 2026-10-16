package nl.kmc.laser;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PuzzleDefTest {

    private static Map<String, Object> comp(String type, int x, int y, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("at", List.of(x, y));
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> easy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "easy_corner");
        m.put("difficulty", "EASY");
        m.put("size", List.of(7, 5));
        m.put("components", List.of(
                comp("EMITTER", 0, 2, "dir", "EAST", "color", "RED"),
                comp("MIRROR", 3, 2, "orient", "BACKSLASH", "id", "m1"),
                comp("RECEIVER", 3, 0, "id", "r1")));
        return m;
    }

    @Test
    void aGoodPuzzleParsesAndValidates() {
        List<String> problems = new ArrayList<>();
        PuzzleDef def = PuzzleDef.parse(easy(), 100, 90, problems);
        assertNotNull(def, problems.toString());
        assertEquals(100, def.points());
        assertEquals(PuzzleDef.Difficulty.EASY, def.difficulty());
        assertTrue(def.validate().isEmpty(), def.validate().toString());
    }

    @Test
    void aPuzzleThatIsAlreadySolvedIsRejected() {
        Map<String, Object> m = easy();
        ((Map<String, Object>) ((List<?>) m.get("components")).get(1)).put("orient", "SLASH");
        PuzzleDef def = PuzzleDef.parse(m, 100, 90, new ArrayList<>());
        assertNotNull(def);
        assertTrue(def.validate().stream().anyMatch(s -> s.contains("al opgelost")));
    }

    @Test
    void anUnsolvablePuzzleIsRejected() {
        Map<String, Object> m = easy();
        m.put("walls", List.of("3,1"));           // the only route north is walled off
        PuzzleDef def = PuzzleDef.parse(m, 100, 90, new ArrayList<>());
        assertNotNull(def);
        assertTrue(def.validate().stream().anyMatch(s -> s.contains("geen oplossing")));
    }

    @Test
    void badConfigurationGivesUsefulMessages() {
        List<String> problems = new ArrayList<>();
        Map<String, Object> m = easy();
        m.put("difficulty", "NIGHTMARE");
        assertNull(PuzzleDef.parse(m, 100, 90, problems));
        assertTrue(problems.get(0).contains("NIGHTMARE"));

        problems.clear();
        m = easy();
        m.put("size", List.of(1, 1));
        assertNull(PuzzleDef.parse(m, 100, 90, problems));
        assertFalse(problems.isEmpty());

        problems.clear();
        m = easy();
        ((List<Object>) new ArrayList<>((List<?>) m.get("components"))).size();
        List<Object> comps = new ArrayList<>((List<?>) m.get("components"));
        comps.add(comp("MIRROR", 9, 9, "id", "far"));
        m.put("components", comps);
        assertNull(PuzzleDef.parse(m, 100, 90, problems));
        assertTrue(problems.get(0).contains("buiten het bord"));
    }

    @Test
    void generatedPuzzlesAreAlwaysSolvableAndNeverStartSolved() {
        Random rnd = new Random(42);
        int made = 0;
        for (int i = 0; i < 60; i++) {
            PuzzleDef.Difficulty d = i % 2 == 0 ? PuzzleDef.Difficulty.EASY : PuzzleDef.Difficulty.MEDIUM;
            PuzzleDef def = PuzzleGenerator.generate("g" + i, d, 9, 9, d == PuzzleDef.Difficulty.EASY ? 2 : 4, 100, 90, rnd);
            if (def == null) continue;
            made++;
            assertTrue(def.validate().isEmpty(), def.validate().toString());
            assertFalse(LaserSim.solved(def.board(), PuzzleDef.MAX_STEPS));
            assertNotNull(LaserSim.solve(def.board(), PuzzleDef.MAX_STEPS));
        }
        assertTrue(made >= 40, "the generator should succeed for most attempts, made " + made);
    }
}
