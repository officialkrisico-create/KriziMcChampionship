package nl.kmc.kmccore.host;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HostTextTest {

    @Test
    void colourCodesAreTranslatedAndStripped() {
        assertEquals("§6Welkom", HostText.color("&6Welkom"));
        assertEquals("R&S", HostText.color("R&S"), "an ampersand that is not a code stays");
        assertEquals("Welkom", HostText.strip("&6§lWelkom"));
        assertEquals(6, HostText.visibleLength("§a§lAbcdef"));
    }

    @Test
    void shortTextIsOneLine() {
        assertEquals(List.of("Welkom bij KMC!"), HostText.wrap("Welkom bij KMC!", 26));
    }

    @Test
    void longTextWrapsWithinTheWidth() {
        String t = "Verdien punten, help je team en probeer bovenaan het leaderboard te eindigen.";
        for (String line : HostText.wrap(t, 26)) assertTrue(HostText.visibleLength(line) <= 26, line);
        assertEquals(t, String.join(" ", HostText.wrap(t, 26)));
    }

    @Test
    void aColourSurvivesALineBreak() {
        List<String> lines = HostText.wrap("&cdit is een lange rode zin die over twee regels loopt", 20);
        assertTrue(lines.size() >= 2);
        for (String l : lines) assertTrue(l.startsWith("§c"), "every line keeps the colour: " + l);
    }

    @Test
    void aNewColourReplacesTheOldOneOnTheNextLine() {
        List<String> lines = HostText.wrap("&caaaa bbbb cccc &9dddd eeee ffff gggg", 10);
        String last = lines.get(lines.size() - 1);
        assertTrue(last.startsWith("§9"), last);
    }

    @Test
    void aWordLongerThanTheWidthIsCut() {
        for (String l : HostText.wrap("Abcdefghijklmnopqrstuvwxyzabcdefghijklmnop", 12)) assertTrue(HostText.visibleLength(l) <= 12);
    }

    @Test
    void paginationGroupsLinesIntoPages() {
        String t = "een twee drie vier vijf zes zeven acht negen tien elf twaalf dertien veertien vijftien zestien";
        List<String> pages = HostText.paginate(t, 14, 2);
        assertTrue(pages.size() > 1);
        for (String p : pages) assertTrue(p.split("\n").length <= 2);
        assertEquals(1, HostText.paginate("kort", 26, 3).size());
        assertEquals(1, HostText.paginate("", 26, 3).size());
    }

    @Test
    void aSentenceIsNeverCutWhenItFitsOnTheNextPage() {
        String t = "Dit is de eerste zin van de host. Dit is de tweede zin die erna komt. En een derde.";
        List<String> pages = HostText.paginate(t, 26, 3);
        for (String p : pages) {
            String flat = p.replace("\n", " ");
            assertTrue(flat.endsWith("."), "a page ends at a sentence end: " + flat);
            assertTrue(p.split("\n").length <= 3);
        }
        assertTrue(pages.size() >= 2);
    }

    @Test
    void aSentenceLongerThanAPageIsStillCutAtWords() {
        String t = "een twee drie vier vijf zes zeven acht negen tien elf twaalf dertien veertien vijftien zestien zeventien achttien.";
        List<String> pages = HostText.paginate(t, 14, 2);
        assertTrue(pages.size() > 1);
        for (String p : pages) assertTrue(p.split("\n").length <= 2);
    }

    @Test
    void colourCodesCarryOverToTheNextPage() {
        String t = "§7§oEen eerste zin van de legende. Een tweede zin die op een nieuwe pagina komt te staan.";
        List<String> pages = HostText.paginate(t, 20, 2);
        assertTrue(pages.size() >= 2);
        assertTrue(pages.get(1).startsWith("§7§o"), pages.get(1));
    }

    @Test
    void placeholdersAreReplacedAndUnknownOnesReported() {
        Set<String> known = Set.of("team", "game");
        var ex = HostText.expand("{team} speelt {game} en {bogus}", Map.of("team", "Rode Ratten", "game", "Blockball"), known);
        assertEquals("Rode Ratten speelt Blockball en ", ex.text());
        assertEquals(Set.of("bogus"), ex.unknown());
        assertTrue(ex.empty().isEmpty());
    }

    @Test
    void aKnownPlaceholderWithoutAValueIsReportedEmpty() {
        var ex = HostText.expand("Leider: {leader}", Map.of(), Set.of("leader"));
        assertEquals(Set.of("leader"), ex.empty());
    }

    @Test
    void bracesThatAreNotPlaceholdersStayAlone() {
        var ex = HostText.expand("Score {3 - 1} en {x y}", Map.of(), Set.of());
        assertEquals("Score {3 - 1} en {x y}", ex.text());
        assertTrue(ex.unknown().isEmpty());
    }

    @Test
    void placeholderNamesAreListed() {
        assertEquals(List.of("a", "b_c"), List.copyOf(HostText.placeholders("{a} en {b_c} en {a}")));
    }
}
