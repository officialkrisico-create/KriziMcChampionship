package nl.kmc.kmccore.platform;

import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlatformUtilTest {

    @Test
    void hudNormalLeavesLinesAlone() {
        List<String> in = List.of("a", "", "b");
        assertSame(in, HudScale.apply("normal", in));
    }

    @Test
    void hudCompactDropsBlankLinesAndCaps() {
        List<String> in = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) { in.add("line " + i); in.add("§r"); }
        List<String> out = HudScale.apply("compact", in);
        assertEquals(10, out.size());
        assertFalse(out.stream().anyMatch(l -> ChatColor.stripColor(l).isBlank()));
    }

    @Test
    void hudMinimalKeepsFive() {
        List<String> in = List.of("1", "2", "3", "4", "5", "6", "7");
        assertEquals(List.of("1", "2", "3", "4", "5"), HudScale.apply("minimal", in));
    }

    @Test
    void hudCycleVisitsEveryMode() {
        assertEquals("compact", HudScale.next("normal"));
        assertEquals("minimal", HudScale.next("compact"));
        assertEquals("normal", HudScale.next("minimal"));
    }

    @Test
    void jsonEscapesQuotesAndControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\"", LiveApiServer.q("a\"b\\c\n"));
        assertEquals("\"\\u0001\"", LiveApiServer.q("\u0001"));
    }

    @Test
    void chatColourToHex() {
        assertEquals("#FF5555", LiveApiServer.hex(ChatColor.RED));
        assertEquals("#FFFFFF", LiveApiServer.hex(null));
    }

    @Test
    void emblemDyeIsStablePerChatColour() {
        assertEquals(DyeColor.RED, TeamEmblems.dyeFor(ChatColor.RED));
        assertEquals(DyeColor.LIME, TeamEmblems.dyeFor(ChatColor.GREEN));
        assertEquals(DyeColor.WHITE, TeamEmblems.dyeFor(null));
    }

    @Test
    void emblemPatternIsDeterministicAndCaseInsensitive() {
        assertEquals(TeamEmblems.patternKeyFor("Rood"), TeamEmblems.patternKeyFor("rood"));
        assertNotNull(TeamEmblems.patternKeyFor(null));
    }
}
