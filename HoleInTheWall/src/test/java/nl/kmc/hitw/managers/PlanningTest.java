package nl.kmc.hitw.managers;

import nl.kmc.hitw.config.EventDef;
import nl.kmc.hitw.config.Modifiers;
import nl.kmc.hitw.config.PatternDef;
import nl.kmc.hitw.wall.WallSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlanningTest {

    private static PatternDef pattern(String name, double weight, double min, double max, String type, int count) {
        return new PatternDef(name, weight, min, max, 0, 1, List.of(new PatternDef.Step(type, count)));
    }

    @Test
    void aPatternKeepsOneDirectionAndTheNextPatternChangesIt() {
        PatternPlanner planner = new PatternPlanner(
                List.of(pattern("a", 1, 0, 1e9, "NORMAL", 3), pattern("b", 1, 0, 1e9, "LOW", 3)),
                List.of(0.0, 90.0, 180.0, 270.0), new Random(7));
        for (int round = 0; round < 20; round++) {
            WallSpec first = planner.poll(0, Map.of());
            WallSpec second = planner.poll(0, Map.of());
            WallSpec third = planner.poll(0, Map.of());
            assertFalse(first.differentSide(second));
            assertFalse(second.differentSide(third));
            WallSpec nextPatternFirst = planner.peek(0, Map.of());
            assertTrue(third.differentSide(nextPatternFirst), "a new pattern must come from another side");
        }
    }

    @Test
    void peekDoesNotConsumeAndMatchesWhatPollReturns() {
        PatternPlanner planner = new PatternPlanner(List.of(pattern("a", 1, 0, 1e9, "NORMAL", 2)), List.of(0.0, 90.0), new Random(1));
        WallSpec peeked = planner.peek(0, Map.of());
        assertEquals(peeked, planner.peek(0, Map.of()));
        assertEquals(peeked, planner.poll(0, Map.of()));
    }

    @Test
    void patternsOnlyAppearInsideTheirTimeWindow() {
        PatternPlanner planner = new PatternPlanner(
                List.of(pattern("early", 1, 0, 10, "NORMAL", 1), pattern("late", 1, 30, 1e9, "FAST", 1)),
                List.of(0.0, 90.0), new Random(3));
        for (int i = 0; i < 30; i++) assertEquals("NORMAL", planner.poll(5, Map.of()).typeId());
        for (int i = 0; i < 30; i++) assertEquals("FAST", planner.poll(60, Map.of()).typeId());
    }

    @Test
    void patternWeightModifiersSteerTheChoice() {
        PatternPlanner planner = new PatternPlanner(
                List.of(pattern("plain", 1, 0, 1e9, "NORMAL", 1), pattern("jumpy", 1, 0, 1e9, "LOW", 1),
                        pattern("other", 1, 0, 1e9, "SPLIT", 1)),
                List.of(0.0, 90.0), new Random(11));
        int low = 0;
        for (int i = 0; i < 400; i++) if (planner.poll(0, Map.of("jumpy", 1000.0)).typeId().equals("LOW")) low++;
        assertTrue(low > 150, "jumpy was picked only " + low + "/400 times despite a x1000 weight (it can't repeat back-to-back)");
    }

    @Test
    void anEmptyPatternConfigStillProducesWalls() {
        PatternPlanner planner = new PatternPlanner(List.of(), List.of(), new Random(1));
        assertNotNull(planner.poll(0, Map.of()));
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private static EventDef event(String id, double duration, double speedMult, double gapScale, double bonus) {
        return new EventDef(id, 1, duration, id, "", "", speedMult, 1, gapScale, bonus, Map.of());
    }

    @Test
    void eventsStartAfterTheConfiguredDelayAndNeverOverlap() {
        EventScheduler s = new EventScheduler(List.of(event("surge", 8, 1.5, 1, 0)), true, 15, 10, 10, new Random(1));
        assertNull(s.update(14.9));
        assertNotNull(s.update(15.0));
        assertTrue(s.hasActive());
        assertNull(s.update(16), "no second event while one is running");
        assertNull(s.update(24), "ended at 23, but the minimum gap hasn't passed yet");
        assertFalse(s.hasActive());
        assertNotNull(s.update(33.0));
    }

    @Test
    void modifiersCombineMultiplicativelyAndGoBackToNormalWhenEventsEnd() {
        EventScheduler s = new EventScheduler(List.of(event("surge", 5, 2.0, 0.5, 1.0)), true, 0, 100, 100, new Random(1));
        assertEquals(Modifiers.NONE.speedMult(), s.modifiers().speedMult());
        s.update(0);
        assertEquals(2.0, s.modifiers().speedMult(), 1e-9);
        assertEquals(0.5, s.modifiers().gapScale(), 1e-9);
        assertEquals(1.0, s.modifiers().speedBonus(), 1e-9);
        s.update(6);
        assertEquals(1.0, s.modifiers().speedMult(), 1e-9);
        assertEquals(0.0, s.modifiers().speedBonus(), 1e-9);
    }

    @Test
    void instantEventsKeepTheirSpeedBonusForTheRestOfTheRound() {
        EventScheduler s = new EventScheduler(List.of(event("spike", 0, 1, 1, 2.5)), true, 0, 1, 1, new Random(1));
        assertNotNull(s.update(0));
        assertFalse(s.hasActive());
        assertEquals(2.5, s.modifiers().speedBonus(), 1e-9);
        s.reset();
        assertEquals(0, s.modifiers().speedBonus(), 1e-9);
    }

    @Test
    void disabledOrEmptySchedulersNeverFire() {
        assertNull(new EventScheduler(List.of(event("x", 5, 1, 1, 0)), false, 0, 1, 1, new Random(1)).update(100));
        assertNull(new EventScheduler(new ArrayList<>(), true, 0, 1, 1, new Random(1)).update(100));
    }

    @Test
    void everyEventIsEventuallyPickedWhenWeightedEqually() {
        List<EventDef> defs = List.of(event("a", 1, 1, 1, 0), event("b", 1, 1, 1, 0), event("c", 1, 1, 1, 0));
        EventScheduler s = new EventScheduler(defs, true, 0, 0, 0, new Random(5));
        Set<String> seen = new HashSet<>();
        for (double t = 0; t < 400; t += 2) { EventDef d = s.update(t); if (d != null) seen.add(d.id()); }
        assertEquals(Set.of("a", "b", "c"), seen);
    }
}
