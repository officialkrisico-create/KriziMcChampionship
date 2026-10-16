package nl.kmc.factory;

import nl.kmc.factory.FactoryModel.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FactoryModelTest {

    private static Recipe smelt() { return new Recipe("metal", Map.of("iron", 2), Map.of("metal", 1), 20); }

    private static MachineType smelter(int queue, int cap) {
        return new MachineType("smelter", queue, cap, List.of(smelt(), new Recipe("glass", Map.of("sand", 1), Map.of("glass", 1), 10)));
    }

    private static OrderDef order(String id, Difficulty d, Map<String, Integer> items) {
        return new OrderDef(id, id, d, items, 100, 0, 0);
    }

    @Test
    void inputIsConsumedOnceAndOutputAppearsOnce() {
        Machine m = new Machine("s1", smelter(3, 10));
        assertEquals(MachineState.IDLE, m.state());
        assertEquals(4, m.insert("iron", 4));
        m.tick(2, 1.0);
        assertEquals(MachineState.PROCESSING, m.state());
        assertEquals(0, m.input().getOrDefault("iron", 0), "both jobs started: 4 iron consumed once");
        m.tick(20, 1.0);
        assertEquals(1, m.output().get("metal"));
        assertEquals(MachineState.PROCESSING, m.state(), "the second job is still running");
        m.tick(20, 1.0);
        assertEquals(2, m.output().get("metal"));
        assertEquals(MachineState.READY, m.state());
        assertEquals(Map.of("metal", 2), m.collect());
        assertEquals(MachineState.IDLE, m.state());
        assertTrue(m.collect().isEmpty(), "an output can be taken only once");
    }

    @Test
    void onlyRecipeInputsAreAcceptedAndWaitingIsReported() {
        Machine m = new Machine("s1", smelter(2, 10));
        assertEquals(0, m.insert("wood", 5));
        assertEquals(1, m.insert("iron", 1));
        m.tick(5, 1.0);
        assertEquals(MachineState.WAITING_FOR_INPUT, m.state());
        assertEquals(Map.of("iron", 1), m.returnInputs());
        assertEquals(MachineState.IDLE, m.state());
    }

    @Test
    void theQueueIsBoundedAndTheRestStaysInTheBuffer() {
        Machine m = new Machine("s1", smelter(2, 10));
        m.insert("iron", 10);              // 5 recipes worth
        m.tick(1, 1.0);
        assertEquals(2, m.queued());
        assertEquals(6, m.input().get("iron"));
    }

    @Test
    void aFullOutputBufferBlocksTheMachineUntilCollected() {
        Machine m = new Machine("s1", smelter(1, 1));
        m.insert("iron", 4);
        m.tick(20, 1.0);                   // first metal fits
        m.tick(25, 1.0);                   // second finishes but the buffer (1) is full
        assertEquals(MachineState.BLOCKED, m.state());
        assertEquals(1, m.output().get("metal"));
        m.collect();
        m.tick(1, 1.0);
        assertEquals(1, m.output().get("metal"));
        assertEquals(MachineState.READY, m.state());
    }

    @Test
    void aDisabledMachineDoesNothing() {
        Machine m = new Machine("s1", smelter(2, 10));
        m.setDisabled(true);
        assertEquals(0, m.insert("iron", 2));
        assertEquals(MachineState.DISABLED, m.state());
    }

    @Test
    void speedShortensTheProcessingTime() {
        Machine slow = new Machine("a", smelter(1, 10)), fast = new Machine("b", smelter(1, 10));
        slow.insert("iron", 2);
        fast.insert("iron", 2);
        slow.tick(10, 1.0);
        fast.tick(10, 2.0);
        assertTrue(slow.output().isEmpty());
        assertEquals(1, fast.output().get("metal"));
    }

    @Test
    void validationFindsUnknownItemsAndUnmakeableOrders() {
        Map<String, MachineType> types = Map.of("smelter", smelter(2, 10));
        Set<String> known = Set.of("iron", "sand", "metal", "glass", "gold");
        Set<String> raw = Set.of("iron", "sand");
        assertTrue(FactoryModel.validate(known, raw, types, Set.of("smelter"),
                List.of(order("ok", Difficulty.SIMPLE, Map.of("metal", 2)))).isEmpty());

        var bad = FactoryModel.validate(known, raw, types, Set.of("smelter"),
                List.of(order("gold", Difficulty.SIMPLE, Map.of("gold", 1))));
        assertTrue(bad.stream().anyMatch(s -> s.contains("niet te maken")));

        var noMachine = FactoryModel.validate(known, raw, types, Set.of(),
                List.of(order("m", Difficulty.SIMPLE, Map.of("metal", 1))));
        assertFalse(noMachine.isEmpty(), "no smelter placed: metal cannot be made");

        var unknown = FactoryModel.validate(known, raw, types, Set.of("smelter"),
                List.of(order("u", Difficulty.SIMPLE, Map.of("unobtainium", 1))));
        assertTrue(unknown.stream().anyMatch(s -> s.contains("onbekend")));
    }

    @Test
    void aRecipeCycleWithoutRawMaterialsDoesNotMakeAnythingProducible() {
        MachineType loop = new MachineType("loop", 1, 5, List.of(
                new Recipe("a", Map.of("x", 1), Map.of("y", 1), 5),
                new Recipe("b", Map.of("y", 1), Map.of("x", 1), 5)));
        Set<String> can = FactoryModel.producible(Set.of("iron"), Map.of("loop", loop), Set.of("loop"));
        assertFalse(can.contains("x"));
        assertFalse(can.contains("y"));
    }

    @Test
    void recipesThatCreateValueFromNothingAreRejected() {
        MachineType cheat = new MachineType("cheat", 1, 5,
                List.of(new Recipe("dup", Map.of("iron", 1), Map.of("metal", 9), 5)));
        var problems = FactoryModel.validate(Set.of("iron", "metal"), Set.of("iron"), Map.of("cheat", cheat),
                Set.of("cheat"), List.of());
        assertTrue(problems.stream().anyMatch(s -> s.contains("veel meer")));
    }

    @Test
    void ordersTakeOnlyWhatTheyNeedAndKeepProgress() {
        Order o = new Order(order("o", Difficulty.SIMPLE, Map.of("metal", 4, "glass", 2)), 0);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(3, o.deliver("metal", 3, a));
        assertEquals(1, o.deliver("metal", 5, b), "only the missing one is accepted");
        assertEquals(0, o.deliver("wood", 5, a), "not part of the order");
        assertFalse(o.done());
        assertEquals(2, o.deliver("glass", 2, b));
        assertTrue(o.done());
        assertEquals(0, o.deliver("metal", 1, a), "a finished order accepts nothing more");
        assertEquals(3, o.contributors().get(a));
        assertEquals(3, o.contributors().get(b));
    }

    @Test
    void nextOrderRespectsGatingAndDoesNotRepeatOpenOrders() {
        List<OrderDef> pool = List.of(
                order("s1", Difficulty.SIMPLE, Map.of("a", 1)),
                order("m1", Difficulty.MEDIUM, Map.of("a", 1)),
                order("c1", Difficulty.COMPLEX, Map.of("a", 1)));
        Random rnd = new Random(1);
        for (int i = 0; i < 50; i++)
            assertEquals("s1", FactoryModel.nextOrder(pool, 0, 2, 5, Set.of(), rnd).id());
        for (int i = 0; i < 50; i++) {
            String id = FactoryModel.nextOrder(pool, 3, 2, 5, Set.of(), rnd).id();
            assertNotEquals("c1", id);
        }
        // everything gated or open: still an order (never an empty board)
        assertNotNull(FactoryModel.nextOrder(pool, 0, 2, 5, Set.of("s1"), rnd));
    }

    @Test
    void stationsNeverGiveMoreThanTheyHold() {
        Station s = new Station("iron", "iron", 10);
        assertEquals(4, s.take(4));
        assertEquals(6, s.take(20));
        assertEquals(0, s.take(1));
        s.restock(3);
        s.giveBack(100);
        assertEquals(10, s.stock());
    }
}
