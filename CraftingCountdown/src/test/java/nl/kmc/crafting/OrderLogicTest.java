package nl.kmc.crafting;

import nl.kmc.crafting.OrderLogic.Difficulty;
import nl.kmc.crafting.OrderLogic.Line;
import nl.kmc.crafting.OrderLogic.PileStock;
import nl.kmc.crafting.OrderLogic.PoolItem;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OrderLogicTest {

    private static final List<PoolItem> POOL = List.of(
            new PoolItem("OAK_LOG", 1, 8, 16, 0.8),
            new PoolItem("COBBLESTONE", 1, 12, 24, 0.5),
            new PoolItem("WHEAT", 1, 8, 16, 0.8),
            new PoolItem("BONE", 2, 6, 12, 1.2),
            new PoolItem("DIAMOND", 3, 2, 4, 6.0));

    @Test
    void ordersOnlyContainObtainableItems() {
        Set<String> obtainable = Set.of("OAK_LOG", "WHEAT", "DIAMOND");
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            List<Line> lines = OrderLogic.generate(POOL, obtainable, new Difficulty(0, 3, 3, 1.0), 48, rnd);
            assertFalse(lines.isEmpty());
            for (Line l : lines) assertTrue(obtainable.contains(l.item()), l.item());
        }
    }

    @Test
    void itemsAboveTheAllowedTierNeverAppear() {
        Set<String> all = Set.of("OAK_LOG", "COBBLESTONE", "WHEAT", "BONE", "DIAMOND");
        Random rnd = new Random(3);
        for (int i = 0; i < 200; i++) {
            for (Line l : OrderLogic.generate(POOL, all, new Difficulty(0, 3, 1, 1.0), 48, rnd))
                assertTrue(Set.of("OAK_LOG", "COBBLESTONE", "WHEAT").contains(l.item()), l.item());
        }
    }

    @Test
    void linesAreDistinctAndQuantitiesAreBounded() {
        Set<String> all = Set.of("OAK_LOG", "COBBLESTONE", "WHEAT", "BONE", "DIAMOND");
        Random rnd = new Random(11);
        for (int i = 0; i < 200; i++) {
            List<Line> lines = OrderLogic.generate(POOL, all, new Difficulty(0, 4, 3, 1.5), 20, rnd);
            assertEquals(lines.size(), lines.stream().map(Line::item).distinct().count());
            for (Line l : lines) { assertTrue(l.required() >= 1); assertTrue(l.required() <= 20); }
        }
    }

    @Test
    void anOrderHoldsTheRarestAvailableTier() {
        Set<String> all = Set.of("OAK_LOG", "COBBLESTONE", "WHEAT", "BONE", "DIAMOND");
        Random rnd = new Random(5);
        for (int i = 0; i < 200; i++) {
            List<Line> lines = OrderLogic.generate(POOL, all, new Difficulty(0, 2, 3, 1.0), 48, rnd);
            assertTrue(lines.stream().anyMatch(l -> l.item().equals("DIAMOND")), lines.toString());
        }
    }

    @Test
    void nothingObtainableGivesNoOrder() {
        assertTrue(OrderLogic.generate(POOL, Set.of("GRAVEL"), new Difficulty(0, 2, 3, 1.0), 48, new Random()).isEmpty());
    }

    @Test
    void difficultyGrowsWithDeliveredOrders() {
        List<Difficulty> table = List.of(new Difficulty(0, 2, 1, 1.0), new Difficulty(3, 3, 2, 1.0),
                new Difficulty(6, 4, 3, 1.5));
        assertEquals(2, OrderLogic.difficultyFor(table, 0).lines());
        assertEquals(2, OrderLogic.difficultyFor(table, 2).lines());
        assertEquals(3, OrderLogic.difficultyFor(table, 3).lines());
        assertEquals(4, OrderLogic.difficultyFor(table, 40).lines());
    }

    @Test
    void itemPointsFollowQuantityAndValue() {
        Map<String, PoolItem> by = new HashMap<>();
        for (PoolItem p : POOL) by.put(p.item(), p);
        int pts = OrderLogic.itemPoints(List.of(new Line("OAK_LOG", 10), new Line("DIAMOND", 2)), by, 1.0);
        assertEquals(20, pts);               // 10 x 0.8 + 2 x 6.0
        assertEquals(40, OrderLogic.itemPoints(List.of(new Line("OAK_LOG", 10), new Line("DIAMOND", 2)), by, 2.0));
    }

    @Test
    void speedBonusFadesBetweenParAndDoublePar() {
        assertEquals(25, OrderLogic.speedBonus(100, 200, 25));
        assertEquals(25, OrderLogic.speedBonus(200, 200, 25));
        assertEquals(13, OrderLogic.speedBonus(300, 200, 25));   // halfway: 12.5 rounds up
        assertEquals(0, OrderLogic.speedBonus(400, 200, 25));
        assertEquals(0, OrderLogic.speedBonus(10, 200, 0));
    }

    @Test
    void streakBonusStartsAtTheSecondOrderAndIsCapped() {
        assertEquals(0, OrderLogic.streakBonus(1, 5, 25));
        assertEquals(5, OrderLogic.streakBonus(2, 5, 25));
        assertEquals(15, OrderLogic.streakBonus(4, 5, 25));
        assertEquals(25, OrderLogic.streakBonus(30, 5, 25));
    }

    @Test
    void splitAddsUpAndFollowsTheWeights() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<UUID, Integer> w = new LinkedHashMap<>();
        w.put(a, 10); w.put(b, 5); w.put(c, 1);
        Map<UUID, Integer> parts = OrderLogic.split(100, w);
        assertEquals(100, parts.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(parts.get(a) > parts.get(b) && parts.get(b) > parts.get(c));
    }

    @Test
    void splitIgnoresZeroWeightsAndEmptyInput() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Map<UUID, Integer> w = new LinkedHashMap<>();
        w.put(a, 0); w.put(b, 4);
        assertEquals(Map.of(b, 7), OrderLogic.split(7, w));
        assertTrue(OrderLogic.split(7, Map.of()).isEmpty());
        assertTrue(OrderLogic.split(0, Map.of(a, 3)).isEmpty());
    }

    @Test
    void pileNeverGivesMoreThanItHolds() {
        PileStock st = new PileStock(10);
        assertEquals(8, st.take(8));
        assertEquals(2, st.take(8));
        assertEquals(0, st.take(8));
        st.add(4);
        assertEquals(4, st.amount());
        st.add(100);
        assertEquals(10, st.amount());
    }

    @Test
    void carryRoomNeverGoesNegative() {
        assertEquals(28, OrderLogic.carryRoom(100, 128));
        assertEquals(0, OrderLogic.carryRoom(140, 128));
    }

    @Test
    void prettyNames() {
        assertEquals("Oak Log", OrderLogic.pretty("OAK_LOG"));
        assertEquals("Lapis Lazuli", OrderLogic.pretty("LAPIS_LAZULI"));
    }
}
