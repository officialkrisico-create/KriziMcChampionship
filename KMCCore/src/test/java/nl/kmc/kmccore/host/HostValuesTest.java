package nl.kmc.kmccore.host;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostValuesTest {

    @Test
    void seatsAreWrittenInDutchWords() {
        assertEquals("vier", HostValues.seatsWord(4));
        assertEquals("twee", HostValues.seatsWord(2));
        assertEquals("twaalf", HostValues.seatsWord(12));
        assertEquals("15", HostValues.seatsWord(15), "larger numbers stay digits");
        assertEquals("Vier", HostValues.capitalize(HostValues.seatsWord(4)));
    }

    @Test
    void theSeatPlaceholdersAreKnown() {
        assertTrue(HostDialogues.KNOWN.containsAll(java.util.List.of("seats", "seats_word", "seats_cap")));
    }
}
