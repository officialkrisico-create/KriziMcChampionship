package nl.kmc.factory;

import nl.kmc.factory.Kitchen.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KitchenTest {

    private static Config cfg() {
        Map<String, String> chop = new LinkedHashMap<>();
        chop.put("tomato", "chopped_tomato");
        chop.put("lettuce", "chopped_lettuce");
        List<CookRecipe> cook = List.of(
                new CookRecipe("pot", Map.of("chopped_tomato", 3), "tomato_soup", 40, true),
                new CookRecipe("pan", Map.of("meat", 1), "patty", 20, false));
        List<Assembly> asm = List.of(
                new Assembly("salad", Map.of("chopped_tomato", 1, "chopped_lettuce", 1)),
                new Assembly("burger", Map.of("bun", 1, "patty", 1, "chopped_lettuce", 1)));
        return new Config(chop, cook, asm, 3, 2, 60, 2, 100);
    }

    private static Station st(Kind k, String item) { return new Station(k, k.name().toLowerCase(), item); }

    @Test
    void aCrateHandsOutOneItemToAnEmptyHandOnly() {
        Station crate = st(Kind.CRATE, "tomato");
        assertEquals("tomato", Kitchen.interact(cfg(), new Team(1), crate, null).hand());
        Result full = Kitchen.interact(cfg(), new Team(1), crate, "onion");
        assertEquals("onion", full.hand());
        assertFalse(full.changed());
    }

    @Test
    void choppingTakesSeveralClicksAndTheResultIsPickedUp() {
        Config c = cfg();
        Station board = st(Kind.BOARD, "");
        Team t = new Team(1);
        assertNull(Kitchen.interact(c, t, board, "tomato").hand());           // placed
        assertEquals("chop", Kitchen.interact(c, t, board, null).msg());
        assertEquals("chop", Kitchen.interact(c, t, board, null).msg());
        assertEquals("chopped", Kitchen.interact(c, t, board, null).msg());   // third click
        assertEquals("chopped_tomato", Kitchen.interact(c, t, board, null).hand());
        assertNull(board.board);
        assertEquals("cannot-chop", Kitchen.interact(c, t, board, "bun").msg());
    }

    @Test
    void aPotCooksWhenFullAndFoodLeftAloneBurns() {
        Config c = cfg();
        Station pot = st(Kind.POT, "pot");
        Team t = new Team(1);
        for (int i = 0; i < 2; i++) assertEquals("added", Kitchen.interact(c, t, pot, "chopped_tomato").msg());
        assertEquals("cooking", Kitchen.interact(c, t, pot, "chopped_tomato").msg());
        assertEquals("pot-busy", Kitchen.interact(c, t, pot, "chopped_tomato").msg());
        for (int i = 0; i < 40; i++) Kitchen.tick(c, pot);
        assertEquals("tomato_soup", pot.ready);
        // soup comes out with a plate, not with a bare hand
        assertEquals("needs-plate", Kitchen.interact(c, t, pot, null).msg());
        Result plated = Kitchen.interact(c, t, pot, "plate");
        assertEquals("dish_tomato_soup", plated.hand());
        assertNull(pot.ready);

        // left alone it burns
        Station pot2 = st(Kind.POT, "pot");
        for (int i = 0; i < 3; i++) Kitchen.interact(c, t, pot2, "chopped_tomato");
        for (int i = 0; i < 40 + 60; i++) Kitchen.tick(c, pot2);
        assertTrue(pot2.burnt);
        assertEquals("burnt", Kitchen.interact(c, t, pot2, "plate").msg());
        assertEquals("cleaned", Kitchen.interact(c, t, pot2, null).msg());
        assertFalse(pot2.burnt);
    }

    @Test
    void aPanTakesMeatAndGivesAPattyByHand() {
        Config c = cfg();
        Station pan = st(Kind.POT, "pan");
        Team t = new Team(1);
        assertEquals("cooking", Kitchen.interact(c, t, pan, "meat").msg());
        assertEquals("pot-refuses", Kitchen.interact(c, t, st(Kind.POT, "pan"), "chopped_tomato").msg());
        for (int i = 0; i < 20; i++) Kitchen.tick(c, pan);
        assertEquals("patty", Kitchen.interact(c, t, pan, null).hand());
    }

    @Test
    void aDishIsAssembledOnAPlateAndOnlyTheRightParts() {
        Config c = cfg();
        Station counter = st(Kind.COUNTER, "");
        Team t = new Team(1);
        assertNull(Kitchen.interact(c, t, counter, "plate").hand());
        assertEquals("not-on-plate", Kitchen.interact(c, t, counter, "meat").msg());
        assertEquals("added", Kitchen.interact(c, t, counter, "bun").msg());
        assertEquals("not-on-plate", Kitchen.interact(c, t, counter, "chopped_tomato").msg());   // a burger needs lettuce, not tomato
        assertEquals("added", Kitchen.interact(c, t, counter, "patty").msg());
        Result done = Kitchen.interact(c, t, counter, "chopped_lettuce");
        assertEquals("dish-ready", done.msg());
        assertEquals("dish_burger", Kitchen.interact(c, t, counter, null).hand());
        assertFalse(counter.plate);
    }

    @Test
    void aCounterIsAlsoATableForOneLooseItem() {
        Config c = cfg();
        Station counter = st(Kind.COUNTER, "");
        Team t = new Team(1);
        assertNull(Kitchen.interact(c, t, counter, "tomato").hand());
        assertEquals("counter-busy", Kitchen.interact(c, t, counter, "bun").msg());
        assertEquals("tomato", Kitchen.interact(c, t, counter, null).hand());
    }

    @Test
    void servingOffersTheDishAndPlatesGoThroughTheSink() {
        Config c = cfg();
        Team t = new Team(2);
        Station serve = st(Kind.SERVE, "");
        Result r = Kitchen.interact(c, t, serve, "dish_salad");
        assertEquals("salad", r.serve());
        assertEquals("serve-dish", Kitchen.interact(c, t, serve, "bun").msg());

        Station rack = st(Kind.RACK, "");
        assertEquals("plate", Kitchen.interact(c, t, rack, null).hand());
        assertEquals("plate", Kitchen.interact(c, t, rack, null).hand());
        assertEquals("no-plates", Kitchen.interact(c, t, rack, null).msg());

        Station sink = st(Kind.SINK, "");
        t.dirty = 1;
        assertEquals("wash", Kitchen.interact(c, t, sink, null).msg());
        assertEquals("washed", Kitchen.interact(c, t, sink, null).msg());
        assertEquals(1, t.plates);
        assertEquals(0, t.dirty);
        assertEquals("nothing-to-wash", Kitchen.interact(c, t, sink, null).msg());
    }

    @Test
    void theBinKeepsTheFoodLostAndGivesTheDirtyPlateBack() {
        Config c = cfg();
        Team t = new Team(0);
        Station bin = st(Kind.TRASH, "");
        assertNull(Kitchen.interact(c, t, bin, "dish_burger").hand());
        assertEquals(1, t.dirty);
        assertNull(Kitchen.interact(c, t, bin, "bun").hand());
        assertEquals(1, t.dirty);
    }

    @Test
    void validationFindsADishNobodyCanMake() {
        Config c = cfg();
        Set<String> crates = Set.of("tomato", "lettuce", "meat", "bun");
        Set<Kind> kinds = EnumSet.allOf(Kind.class);
        assertTrue(Kitchen.validate(c, crates, kinds, Set.of("pot", "pan"), List.of("salad", "burger", "tomato_soup")).isEmpty());
        // no pan: the patty (and so the burger) cannot be made
        var p = Kitchen.validate(c, crates, kinds, Set.of("pot"), List.of("burger"));
        assertTrue(p.stream().anyMatch(s -> s.contains("burger")), p.toString());
        // no sink
        var q = Kitchen.validate(c, crates, EnumSet.complementOf(EnumSet.of(Kind.SINK)), Set.of("pot", "pan"), List.of("salad"));
        assertTrue(q.stream().anyMatch(s -> s.contains("sink")), q.toString());
    }
}
