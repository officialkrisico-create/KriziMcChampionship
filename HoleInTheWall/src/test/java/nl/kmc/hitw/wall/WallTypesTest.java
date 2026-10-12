package nl.kmc.hitw.wall;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WallTypesTest {

    private WallTypes types;

    @BeforeEach
    void setUp() {
        types = new WallTypes();
        types.register(new WallTypeDef("NORMAL", "HOLES", "NORMAL", "ORANGE_CONCRETE", 1, 3.0, 5.0, 0, 0, 1.0, 1.0));
        types.register(new WallTypeDef("DOUBLE_HOLE", "HOLES", "DOUBLE", "PURPLE_CONCRETE", 2, 2.4, 5.0, 0, 0, 1.0, 1.0));
        types.register(new WallTypeDef("SPLIT", "HOLES", "SPLIT", "PINK_CONCRETE", 4, 1.6, 5.0, 0, 0, 1.0, 1.0));
        types.register(new WallTypeDef("LOW", "BAR", "LOW", "YELLOW_CONCRETE", 0, 0, 0, 0.0, 0.7, 0.5, 1.0));
    }

    private static double solidWidth(WallShape s) {
        return s.solids().stream().mapToDouble(Rect::width).sum();
    }

    @Test
    void holesWallIsTheFullWidthMinusItsGaps() {
        for (int seed = 0; seed < 200; seed++) {
            WallShape wall = types.build("NORMAL", new WallBuildContext(20, 0, 8, 17, 1.0, new Random(seed)));
            assertEquals(40 - 3.0, solidWidth(wall), 1e-9, "seed " + seed);
        }
    }

    @Test
    void doubleAndSplitWallsHaveTheRequestedNumberOfGaps() {
        for (int seed = 0; seed < 100; seed++) {
            WallShape dbl = types.build("DOUBLE_HOLE", new WallBuildContext(20, 0, 8, 17, 1.0, new Random(seed)));
            assertEquals(40 - 2 * 2.4, solidWidth(dbl), 1e-9, "double, seed " + seed);

            WallShape split = types.build("SPLIT", new WallBuildContext(20, 0, 8, 17, 1.0, new Random(seed)));
            double gaps = (40 - solidWidth(split)) / 1.6;
            assertTrue(gaps >= 1 - 1e-9 && gaps <= 4 + 1e-9, "split gap count " + gaps + ", seed " + seed);
        }
    }

    @Test
    void solidsNeverOverlapEachOther() {
        for (int seed = 0; seed < 100; seed++) {
            WallShape wall = types.build("SPLIT", new WallBuildContext(20, 0, 8, 17, 1.0, new Random(seed)));
            List<Rect> r = wall.solids();
            for (int i = 1; i < r.size(); i++) assertTrue(r.get(i).u0() >= r.get(i - 1).u1() - 1e-9, "seed " + seed);
        }
    }

    @Test
    void theMainGapIsAlwaysWithinReachOfTheAnchor() {
        for (int seed = 0; seed < 500; seed++) {
            double anchor = -10 + (seed % 21);
            double reach = 4;
            WallShape wall = types.build("NORMAL", new WallBuildContext(20, anchor, reach, 17, 1.0, new Random(seed)));
            assertTrue(wall.hasGap());
            double c = wall.gapCenter();
            assertTrue(Math.abs(c - anchor) <= reach + 1e-9 || Math.abs(c) >= 17 - 1.5 - 1e-6,
                    "gap " + c + " too far from anchor " + anchor + " (seed " + seed + ")");
        }
    }

    @Test
    void gapsStayInsideThePlatformsUsableWidth() {
        for (int seed = 0; seed < 300; seed++) {
            WallShape wall = types.build("DOUBLE_HOLE", new WallBuildContext(20, 15, 30, 17, 1.0, new Random(seed)));
            // the rectangle boundaries bound every gap; the first solid must reach the wall edge unless a gap sits there
            double leftmostSolidStart = wall.solids().get(0).u0();
            assertTrue(leftmostSolidStart <= -17 + 2.4 || leftmostSolidStart == -20, "seed " + seed);
        }
    }

    @Test
    void shrinkingTheGapScaleNeverMakesAHoleSmallerThanTheMinimum() {
        WallShape tiny = types.build("SPLIT", new WallBuildContext(20, 0, 8, 17, 0.1, new Random(3)));
        double gaps = 40 - solidWidth(tiny);
        assertTrue(gaps >= WallTypes.MIN_GAP - 1e-9, "total open width " + gaps);
    }

    @Test
    void barWallsHaveNoGapAndCoverTheWholeWidth() {
        WallShape low = types.build("LOW", new WallBuildContext(20, 0, 0, 17, 1.0, new Random(1)));
        assertFalse(low.hasGap());
        assertEquals(1, low.solids().size());
        assertEquals(40, low.solids().get(0).width(), 1e-9);
        assertEquals(0.7, low.maxHeight(), 1e-9);
    }

    @Test
    void unknownTypesAndShapesFailLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> types.build("NOPE", new WallBuildContext(20, 0, 0, 17, 1.0, new Random(1))));
        types.register(new WallTypeDef("WEIRD", "SPIRAL", "WEIRD", "STONE", 0, 0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> types.build("WEIRD", new WallBuildContext(20, 0, 0, 17, 1.0, new Random(1))));
    }

    @Test
    void newShapesCanBeRegisteredWithoutTouchingTheCore() {
        types.registerShape("FLOATING", (def, ctx) -> new WallShape(def.id(), def.displayName(), def.material(),
                List.of(new Rect(-ctx.halfWidth(), ctx.halfWidth(), 2, 3)), ctx.halfWidth(), 1, 1, Double.NaN));
        types.register(new WallTypeDef("CEILING", "FLOATING", "CEILING", "STONE", 0, 0, 0, 0, 0, 1, 1));
        assertEquals(1, types.build("CEILING", new WallBuildContext(20, 0, 0, 17, 1.0, new Random(1))).solids().size());
    }

    @Test
    void typeLookupIsCaseInsensitive() {
        assertNotNull(types.get("normal"));
        assertNotNull(types.get("Double_Hole"));
        assertNull(types.get("missing"));
    }
}
