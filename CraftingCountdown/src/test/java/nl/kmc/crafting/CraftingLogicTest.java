package nl.kmc.crafting;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CraftingLogicTest {

    @Test
    void partialDeliveriesAccumulateAndCompleteOnce() {
        TaskProgress t = new TaskProgress(4);
        assertEquals(1, t.offer(1));
        assertEquals(2, t.offer(2));
        assertFalse(t.isDone());
        assertEquals(1, t.offer(5));       // only the missing one is taken
        assertTrue(t.isDone());
        assertEquals(0, t.offer(3));       // nothing more once done
        assertEquals(4, t.delivered());
    }

    @Test
    void nothingTakenWhenNothingAvailable() {
        TaskProgress t = new TaskProgress(2);
        assertEquals(0, t.offer(0));
        assertEquals(0, t.offer(-3));
        assertEquals(2, t.missing());
    }

    @Test
    void invalidRecipesAreReportedAndSkipped() {
        List<Object> raw = new ArrayList<>();
        raw.add(Map.of("id", "ok", "item", "TORCH", "amount", 8, "difficulty", "EASY",
                "resources", Map.of("COAL", 2)));
        raw.add(Map.of("id", "bad-item", "item", "NOT_A_THING", "amount", 1, "difficulty", "EASY",
                "resources", Map.of("COAL", 1)));
        raw.add(Map.of("id", "no-res", "item", "TORCH", "amount", 1, "difficulty", "EASY"));
        raw.add(Map.of("id", "bad-diff", "item", "TORCH", "amount", 1, "difficulty", "IMPOSSIBLE",
                "resources", Map.of("COAL", 1)));
        List<String> problems = new ArrayList<>();
        List<Recipe> parsed = Recipe.parse(raw, problems);
        assertEquals(1, parsed.size());
        assertEquals("ok", parsed.get(0).id());
        assertEquals(3, problems.size());
    }

    @Test
    void pickPrefersTheWantedDifficultyAndUnusedRecipes() {
        List<Recipe> pool = new ArrayList<>();
        pool.add(new Recipe("a", org.bukkit.Material.TORCH, 1, Recipe.Difficulty.EASY, Map.of(org.bukkit.Material.COAL, 1)));
        pool.add(new Recipe("b", org.bukkit.Material.BREAD, 1, Recipe.Difficulty.EASY, Map.of(org.bukkit.Material.WHEAT, 3)));
        pool.add(new Recipe("c", org.bukkit.Material.PISTON, 1, Recipe.Difficulty.HARD, Map.of(org.bukkit.Material.IRON_INGOT, 1)));
        Random rnd = new Random(7);
        for (int i = 0; i < 20; i++) {
            Recipe r = Recipe.pick(pool, Recipe.Difficulty.EASY, Set.of("a"), false, rnd);
            assertEquals("b", r.id());
        }
        // easy pool used up, repeats not allowed: another unused one from a different difficulty
        assertEquals("c", Recipe.pick(pool, Recipe.Difficulty.EASY, Set.of("a", "b"), false, rnd).id());
        // everything used: a repeat instead of a crash
        assertNotNull(Recipe.pick(pool, Recipe.Difficulty.EASY, Set.of("a", "b", "c"), false, rnd));
        assertNull(Recipe.pick(List.of(), Recipe.Difficulty.EASY, Set.of(), true, rnd));
    }
}
