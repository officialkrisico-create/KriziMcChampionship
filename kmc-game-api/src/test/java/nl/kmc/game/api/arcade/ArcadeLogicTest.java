package nl.kmc.game.api.arcade;

import nl.kmc.game.api.arcade.ArcadeLogic.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ArcadeLogicTest {

    // ── Region ──

    @Test
    void regionContainsBlocksInclusive() {
        Region r = Region.ofBlocks("w", 5, 10, 5, 0, 12, 0);
        assertTrue(r.contains(0.0, 10.0, 0.0));
        assertTrue(r.contains(5.99, 12.99, 5.99));
        assertFalse(r.contains(6.0, 11, 3));
        assertTrue(r.containsXZ(3, 3));
        assertFalse(r.containsXZ(-0.1, 3));
    }

    @Test
    void regionShrinksTowardsCentreAndKeepsMinimum() {
        Region r = Region.ofBlocks("w", 0, 0, 0, 19, 5, 19);      // 20 x 20
        Region half = r.shrunk(0.5, 4);
        assertEquals(10, half.sizeX(), 1e-9);
        assertEquals(r.centerX(), half.centerX(), 1e-9);
        Region tiny = r.shrunk(1.0, 6);
        assertEquals(6, tiny.sizeX(), 1e-9);
    }

    // ── OnceGuard ──

    @Test
    void onceGuardOnlyAllowsTheFirst() {
        OnceGuard g = new OnceGuard();
        assertTrue(g.first("a"));
        assertFalse(g.first("a"));
        assertTrue(g.first("b"));
        assertEquals(2, g.size());
        g.clear();
        assertTrue(g.first("a"));
    }

    // ── ScoreBook ──

    @Test
    void scoreBookIgnoresDuplicateKeys() {
        ScoreBook b = new ScoreBook();
        UUID p = UUID.randomUUID();
        assertTrue(b.add("cp:1", p, "red", "checkpoint", 10));
        assertFalse(b.add("cp:1", p, "red", "checkpoint", 10));
        assertEquals(10, b.team("red"));
        assertEquals(10, b.player(p));
        assertEquals(1, b.events().size());
    }

    @Test
    void scoreBookKeylessEventsAlwaysCount() {
        ScoreBook b = new ScoreBook();
        b.add(null, null, "red", "tick", 10);
        b.add(null, null, "red", "tick", 10);
        assertEquals(20, b.team("red"));
        assertEquals(20, b.categoryTotal("red", "tick"));
    }

    @Test
    void tieBreakIsDeterministic() {
        ScoreBook b = new ScoreBook();
        b.add(null, null, "blue", "x", 50);     // reached 50 first
        b.add(null, null, "red", "x", 50);
        assertEquals(List.of("blue", "red", "green"), b.rankTeams(List.of("red", "green", "blue")));
        // never-scored teams are ordered by id
        assertEquals(List.of("blue", "red", "aqua", "zed"), b.rankTeams(List.of("zed", "aqua", "red", "blue")));
    }

    @Test
    void playersRankedByPointsThenId() {
        ScoreBook b = new ScoreBook();
        UUID a = new UUID(0, 1), c = new UUID(0, 2);
        b.add(null, c, "t", "x", 5);
        assertEquals(List.of(c, a), b.rankPlayers(List.of(a, c)));
    }

    // ── CaptureMeter ──

    @Test
    void captureNeedsTheFullDurationWithOneTeam() {
        CaptureMeter m = new CaptureMeter(5, 1);
        for (int i = 0; i < 4; i++) assertNull(m.update(Set.of("red"), 1));
        assertEquals(CaptureStatus.CAPTURING, m.status());
        CaptureMeter.Change c = m.update(Set.of("red"), 1);
        assertNotNull(c);
        assertEquals("red", c.owner());
        assertNull(c.previous());
        assertEquals(CaptureStatus.CONTROLLED, m.status());
    }

    @Test
    void contestedPausesTheMeter() {
        CaptureMeter m = new CaptureMeter(5, 1);
        m.update(Set.of("red"), 3);
        assertEquals(CaptureStatus.CONTESTED, m.update(Set.of("red", "blue"), 10) == null ? m.status() : null);
        double before = m.fraction();
        m.update(Set.of("red", "blue"), 10);
        assertEquals(before, m.fraction(), 1e-9);
        // red alone again continues from where it was
        assertNull(m.update(Set.of("red"), 1));
        assertNotNull(m.update(Set.of("red"), 1));
    }

    @Test
    void ownerStandingThereDoesNotRecapture() {
        CaptureMeter m = new CaptureMeter(2, 1);
        m.setOwner("red");
        for (int i = 0; i < 10; i++) assertNull(m.update(Set.of("red"), 1));
        assertEquals("red", m.owner());
    }

    @Test
    void takeoverReportsPreviousOwner() {
        CaptureMeter m = new CaptureMeter(2, 1);
        m.setOwner("red");
        m.update(Set.of("blue"), 1);
        CaptureMeter.Change c = m.update(Set.of("blue"), 1);
        assertEquals("blue", c.owner());
        assertEquals("red", c.previous());
    }

    @Test
    void progressDecaysWhenEmptyAndRestartsForAnotherTeam() {
        CaptureMeter m = new CaptureMeter(10, 2);
        m.update(Set.of("red"), 4);
        m.update(Set.of(), 1);
        assertTrue(m.fraction() < 0.4);
        m.update(Set.of("blue"), 1);          // a different team starts from zero
        assertEquals("blue", m.capturer());
        assertEquals(0.1, m.fraction(), 1e-9);
    }

    // ── TeamEnergy ──

    @Test
    void energyIsCappedAndSpendIsAtomic() {
        TeamEnergy e = new TeamEnergy(100);
        assertEquals(95, e.add(95));
        assertEquals(5, e.add(10), 1e-9);          // 5 accepted, 5 overflow
        assertTrue(e.isFull());
        assertFalse(e.trySpend(101));
        assertEquals(100, e.value(), 1e-9);        // a failed spend removes nothing
        assertTrue(e.trySpend(30));
        assertEquals(70, e.value(), 1e-9);
        assertFalse(e.trySpend(-5));
    }

    @Test
    void energyNeverGoesBelowZero() {
        TeamEnergy e = new TeamEnergy(50);
        e.add(20);
        assertTrue(e.trySpend(20));
        assertFalse(e.trySpend(0.5));
        assertEquals(0, e.value(), 1e-9);
    }

    // ── Cooldowns ──

    @Test
    void cooldownCountsGameTicks() {
        Cooldowns c = new Cooldowns();
        assertTrue(c.ready("speed", 0));
        c.start("speed", 100, 200);
        assertFalse(c.ready("speed", 299));
        assertTrue(c.ready("speed", 300));
        assertEquals(50, c.remaining("speed", 250));
    }

    // ── Geometry / picking ──

    @Test
    void fastFlightCannotSkipACheckpoint() {
        // moves 20 blocks in one poll straight through a checkpoint of radius 3 at x=10
        assertTrue(ArcadeLogic.segmentHitsSphere(0, 0, 0, 20, 0, 0, 10, 0, 0, 3));
        assertFalse(ArcadeLogic.segmentHitsSphere(0, 0, 0, 20, 0, 0, 10, 8, 0, 3));
        assertTrue(ArcadeLogic.segmentHitsSphere(5, 0, 0, 5, 0, 0, 5, 1, 0, 3));   // zero-length segment
    }

    @Test
    void pickDistinctAvoidsThePreviousCombination() {
        List<String> pool = List.of("A", "B", "C");
        Random rnd = new Random(1);
        for (int i = 0; i < 50; i++) {
            List<String> pick = ArcadeLogic.pickDistinct(pool, 2, List.of("A", "B"), rnd);
            assertEquals(2, pick.size());
            assertNotEquals(Set.of("A", "B"), new HashSet<>(pick));
        }
        // single possible combination: returned anyway
        assertEquals(2, ArcadeLogic.pickDistinct(List.of("A", "B"), 2, List.of("A", "B"), rnd).size());
        assertTrue(ArcadeLogic.pickDistinct(List.of(), 2, null, rnd).isEmpty());
    }
}
