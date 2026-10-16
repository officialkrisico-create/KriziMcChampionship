package nl.kmc.kmccore.checklist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class ChecklistTest {

    @TempDir File dir;

    private Checklist fresh() { return new Checklist(new File(dir, "checklist.yml")); }

    @Test
    void aNewChecklistHoldsTheStandardItemsUnticked() {
        Checklist c = fresh();
        assertEquals(Checklist.DEFAULTS.size(), c.size());
        assertEquals(0, c.doneCount());
        assertFalse(c.complete());
    }

    @Test
    void ticksAreRemembered() {
        Checklist c = fresh();
        assertEquals(Boolean.TRUE, c.toggle(0));
        assertEquals(Boolean.TRUE, c.toggle(2));
        Checklist again = fresh();
        assertEquals(2, again.doneCount());
        assertTrue(again.items().get(0).done());
        assertEquals(Boolean.FALSE, again.toggle(0));
        assertNull(again.toggle(999));
    }

    @Test
    void ownItemsCanBeAddedAndRemoved() {
        Checklist c = fresh();
        int before = c.size();
        c.add("  Camera's van de stream testen ");
        assertEquals(before + 1, c.size());
        assertEquals("Camera's van de stream testen", c.items().get(before).text());
        assertFalse(c.items().get(before).standard());
        assertEquals("Camera's van de stream testen", c.remove(before));
        assertEquals(before, fresh().size());
    }

    @Test
    void aRemovedStandardItemStaysRemovedUntilRestored() {
        Checklist c = fresh();
        String first = c.items().get(0).text();
        c.remove(0);
        Checklist again = fresh();
        assertEquals(Checklist.DEFAULTS.size() - 1, again.size());
        assertTrue(again.items().stream().noneMatch(i -> i.text().equals(first)));
        again.restoreDefaults();
        assertEquals(Checklist.DEFAULTS.size(), again.size());
    }

    @Test
    void resetUnticksEverythingAndCompleteNeedsAll() {
        Checklist c = fresh();
        for (int i = 0; i < c.size(); i++) c.toggle(i);
        assertTrue(c.complete());
        c.reset();
        assertEquals(0, c.doneCount());
        assertFalse(c.complete());
    }
}
