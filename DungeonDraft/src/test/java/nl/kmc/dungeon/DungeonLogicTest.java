package nl.kmc.dungeon;

import nl.kmc.dungeon.DungeonLogic.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DungeonLogicTest {

    private static RoomDef room(String id, RoomType t, int diff) {
        return new RoomDef(id, id, "", t, diff, 100, 90, "laag", new int[]{0, 0, 0}, new int[]{1, 0, 1}, Map.of());
    }

    private static List<RoomDef> pool() {
        return List.of(room("a", RoomType.COMBAT, 1), room("b", RoomType.PARKOUR, 1), room("c", RoomType.TRAP, 2),
                room("d", RoomType.TREASURE, 2), room("e", RoomType.PUZZLE, 3), room("f", RoomType.COMBAT, 3),
                room("boss", RoomType.BOSS, 3));
    }

    @Test
    void aGoodPoolValidatesAndAShortOneDoesNot() {
        assertTrue(DungeonLogic.validate(pool(), 5, 3, true).isEmpty());
        var few = DungeonLogic.validate(List.of(room("a", RoomType.COMBAT, 1), room("boss", RoomType.BOSS, 3)), 5, 3, true);
        assertTrue(few.stream().anyMatch(s -> s.contains("te weinig kamers")));
        var noBoss = DungeonLogic.validate(List.of(room("a", RoomType.COMBAT, 1)), 1, 3, true);
        assertTrue(noBoss.stream().anyMatch(s -> s.contains("BOSS")));
        var dup = DungeonLogic.validate(List.of(room("a", RoomType.COMBAT, 1), room("a", RoomType.PARKOUR, 1)), 1, 1, false);
        assertTrue(dup.stream().anyMatch(s -> s.contains("twee keer")));
    }

    @Test
    void difficultyGrowsAlongTheRun() {
        assertEquals(1, DungeonLogic.maxDifficulty(1, 5));
        assertEquals(2, DungeonLogic.maxDifficulty(3, 5));
        assertEquals(3, DungeonLogic.maxDifficulty(5, 5));
    }

    @Test
    void optionsAreUnusedAndBossesOnlyComeLast() {
        Random rnd = new Random(9);
        Set<String> used = new HashSet<>(Set.of("a"));
        for (int i = 0; i < 100; i++) {
            var opts = DungeonLogic.draft(pool(), used, 2, 5, 3, true, 0.0, rnd);
            assertEquals(3, opts.size());
            for (Option o : opts) {
                assertFalse(used.contains(o.room().id()));
                assertNotEquals(RoomType.BOSS, o.room().type());
            }
        }
        var last = DungeonLogic.draft(pool(), used, 5, 5, 3, true, 0.0, rnd);
        assertEquals(1, last.size());
        assertEquals(RoomType.BOSS, last.get(0).room().type());
    }

    @Test
    void earlyRoomsAreNotTooHardAndTypesAreVaried() {
        Random rnd = new Random(2);
        for (int i = 0; i < 100; i++) {
            var opts = DungeonLogic.draft(pool(), Set.of(), 1, 5, 3, true, 0.0, rnd);
            // only two easy rooms exist: both are offered, and the third is the easiest harder room, never a 3
            long easy = opts.stream().filter(o -> o.room().difficulty() == 1).count();
            assertEquals(2, easy);
            assertTrue(opts.stream().allMatch(o -> o.room().difficulty() <= 2));
            var types = new HashSet<RoomType>();
            for (Option o : opts) types.add(o.room().type());
            assertEquals(opts.size(), types.size(), "different room types are preferred");
        }
    }

    @Test
    void thereIsAlwaysAnOptionWhileARoomIsLeft() {
        Random rnd = new Random(4);
        Set<String> used = new HashSet<>();
        for (RoomDef r : pool()) used.add(r.id());
        assertTrue(DungeonLogic.draft(pool(), used, 3, 5, 3, true, 0.0, rnd).isEmpty());
        used.remove("c");
        var opts = DungeonLogic.draft(pool(), used, 3, 5, 3, true, 0.0, rnd);
        assertEquals(1, opts.size());
        assertEquals("c", opts.get(0).room().id());
    }

    @Test
    void modifiersAreOptionalAndNeverOnBosses() {
        Random rnd = new Random(1);
        var all = DungeonLogic.draft(pool(), Set.of(), 5, 5, 3, true, 1.0, rnd);
        assertEquals(Modifier.NONE, all.get(0).modifier());
        var mid = DungeonLogic.draft(pool(), Set.of(), 2, 5, 3, true, 1.0, rnd);
        assertTrue(mid.stream().allMatch(o -> o.modifier() != Modifier.NONE));
        var none = DungeonLogic.draft(pool(), Set.of(), 2, 5, 3, true, 0.0, rnd);
        assertTrue(none.stream().allMatch(o -> o.modifier() == Modifier.NONE));
    }

    @Test
    void theFallbackFollowsTheConfiguredRule() {
        List<Option> o = List.of(new Option(room("a", RoomType.COMBAT, 1), Modifier.NONE),
                new Option(room("b", RoomType.TRAP, 3), Modifier.NONE), new Option(room("c", RoomType.PUZZLE, 2), Modifier.NONE));
        assertEquals("a", DungeonLogic.fallback(o, "EASIEST", new Random()).room().id());
        assertEquals("b", DungeonLogic.fallback(o, "HARDEST", new Random()).room().id());
        assertNotNull(DungeonLogic.fallback(o, "RANDOM", new Random()));
        assertNull(DungeonLogic.fallback(List.of(), "RANDOM", new Random()));
    }

    @Test
    void votesResolveByMajorityLeaderOrTimeout() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<UUID, Integer> v = new LinkedHashMap<>();
        assertEquals(-1, DungeonLogic.resolveVote(v, false, a, 3, new Random()));
        v.put(a, 2); v.put(b, 2); v.put(c, 0);
        assertEquals(2, DungeonLogic.resolveVote(v, false, a, 3, new Random()));
        assertEquals(0, DungeonLogic.resolveVote(v, true, c, 3, new Random()));
        assertEquals(-1, DungeonLogic.resolveVote(Map.of(a, 1), true, b, 3, new Random()), "the leader has not voted yet");
        // a tie picks one of the tied options, never a third
        Map<UUID, Integer> tie = Map.of(a, 0, b, 1);
        for (int i = 0; i < 50; i++) assertTrue(DungeonLogic.resolveVote(tie, false, a, 3, new Random(i)) <= 1);
        // an out-of-range vote is ignored
        assertEquals(-1, DungeonLogic.resolveVote(Map.of(a, 9), false, a, 3, new Random()));
    }

    @Test
    void sharedHealthRevivesOnceThenFails() {
        Health h = new Health(100, 1);
        assertEquals(HealthResult.OK, h.damage(30));
        assertEquals(70, h.current(), 1e-9);
        assertEquals(HealthResult.REVIVED, h.damage(100));
        assertEquals(50, h.current(), 1e-9);
        assertEquals(0, h.revives());
        assertEquals(HealthResult.FAILED, h.damage(60));
        assertTrue(h.failed());
        assertEquals(HealthResult.FAILED, h.damage(1), "a failed run stays failed");
        h.heal(50);
        assertEquals(0, h.current(), "no healing after the run failed");
    }

    @Test
    void healingNeverExceedsTheMaximum() {
        Health h = new Health(100, 0);
        h.damage(10);
        h.heal(500);
        assertEquals(100, h.current(), 1e-9);
    }

    @Test
    void theSequencePuzzleNeedsTheRightOrderAndResetsOnAMistake() {
        DungeonLogic.SequencePuzzle p = new DungeonLogic.SequencePuzzle(4, 4, new Random(5));
        int[] seq = p.sequence();
        assertEquals(4, seq.length);
        assertEquals(DungeonLogic.Press.OK, p.press(seq[0]));
        int wrong = (seq[1] + 1) % 4;
        assertEquals(DungeonLogic.Press.WRONG, p.press(wrong));
        assertEquals(0, p.progress());
        for (int i = 0; i < 3; i++) assertEquals(DungeonLogic.Press.OK, p.press(seq[i]));
        assertEquals(DungeonLogic.Press.DONE, p.press(seq[3]));
        assertTrue(p.done());
        assertEquals(DungeonLogic.Press.DONE, p.press(0), "a solved puzzle stays solved");
    }

    @Test
    void rewardsAndTimeFraction() {
        assertEquals(200, DungeonLogic.roomReward(100, Modifier.DOUBLE_REWARD));
        assertEquals(100, DungeonLogic.roomReward(100, Modifier.REDUCED_TIME));
        assertEquals(0.75, DungeonLogic.timeLeftFraction(1000, 250), 1e-9);
        assertEquals(0, DungeonLogic.timeLeftFraction(0, 250));
        assertEquals(0, DungeonLogic.timeLeftFraction(100, 500));
    }
}
