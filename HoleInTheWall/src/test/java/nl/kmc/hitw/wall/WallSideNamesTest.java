package nl.kmc.hitw.wall;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WallSideNamesTest {

    @Test
    void straightSidesUseTheirColour() {
        assertEquals("Geel", WallSideNames.name(0, null));                     // east
        assertEquals("Blauw", WallSideNames.name(Math.PI / 2, null));          // south
        assertEquals("Rood", WallSideNames.name(Math.PI, null));               // west
        assertEquals("Groen", WallSideNames.name(3 * Math.PI / 2, null));      // north
    }

    @Test
    void cornersCombineBothColours() {
        assertEquals("RoodBlauw", WallSideNames.name(Math.toRadians(135), null));
        assertEquals("BlauwGeel", WallSideNames.name(Math.toRadians(45), null));
    }

    @Test
    void negativeAndLargeAnglesWrap() {
        assertEquals("Groen", WallSideNames.name(-Math.PI / 2, null));
        assertEquals("Geel", WallSideNames.name(Math.PI * 2, null));
    }

    @Test
    void configuredNamesOverrideAndBadListFallsBack() {
        List<String> custom = List.of("A", "B", "C", "D", "E", "F", "G", "H");
        assertEquals("C", WallSideNames.name(Math.PI / 2, custom));
        assertEquals("Rood", WallSideNames.name(Math.PI, List.of("x")));
    }

    @Test
    void coloursEveryWord() {
        assertEquals("§cRood§9Blauw", WallSideNames.colored("RoodBlauw"));
        assertEquals("§aGroen", WallSideNames.colored("Groen"));
    }
}
