package nl.kmc.hitw.wall;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WallCollisionTest {

    private static final double FLOOR = 64;
    private static final double R = 0.3;
    private static final double STAND = 1.8, SNEAK = 1.5;

    private WallTypes types;

    @BeforeEach
    void setUp() {
        types = new WallTypes();
        types.register(new WallTypeDef("NORMAL", "HOLES", "NORMAL", "ORANGE_CONCRETE", 1, 3.0, 5.0, 0, 0, 1.0, 1.0));
        types.register(new WallTypeDef("LOW",    "BAR",   "LOW",    "YELLOW_CONCRETE", 0, 0, 0, 0.0, 0.7, 0.5, 1.0));
        types.register(new WallTypeDef("HIGH",   "BAR",   "HIGH",   "CYAN_CONCRETE",   0, 0, 0, 1.6, 4.0, 0.6, 1.0));
    }

    /** A wall with its single gap exactly at u = 0 (reach 0 pins the gap to the anchor). */
    private WallShape centredGapWall() {
        return types.build("NORMAL", new WallBuildContext(20, 0, 0, 18, 1.0, new Random(1)));
    }

    private boolean hitAt(WallShape wall, double wallS, double px, double py, double pz, double height) {
        // alpha = 0: wall normal is +X, so s == world x and u == world z (arena centre at the origin)
        return WallCollision.hits(wall, 0, 0, FLOOR, wallS, 0, px, py, pz, height, R);
    }

    @Test
    void playerInTheMiddleOfTheGapPasses() {
        assertFalse(hitAt(centredGapWall(), 10, 10, FLOOR, 0, STAND));
    }

    @Test
    void playerInsideSolidPartIsHit() {
        assertTrue(hitAt(centredGapWall(), 10, 10, FLOOR, 6, STAND));
    }

    @Test
    void grazingTheEdgeOfAGapIsNotAHitButOverlappingIsOne() {
        WallShape wall = centredGapWall();                       // gap is u in [-1.5, 1.5]
        assertFalse(hitAt(wall, 10, 10, FLOOR, 1.1, STAND));     // centre 0.4 from the edge, radius 0.3 -> clear
        assertTrue(hitAt(wall, 10, 10, FLOOR, 1.3, STAND));      // centre 0.2 from the edge -> overlaps
        assertFalse(hitAt(wall, 10, 10, FLOOR, -1.1, STAND));    // same on the other side
        assertTrue(hitAt(wall, 10, 10, FLOOR, -1.3, STAND));
    }

    @Test
    void wallOnlyHitsWhileItActuallyOverlapsInTravelDirection() {
        WallShape wall = centredGapWall();                       // thickness 1.0 -> solid for s in [9.5, 10.5]
        assertFalse(hitAt(wall, 10, 13, FLOOR, 6, STAND));       // player well ahead of the wall
        assertFalse(hitAt(wall, 10, 7, FLOOR, 6, STAND));        // player already behind it
        assertTrue(hitAt(wall, 10, 10.7, FLOOR, 6, STAND));      // within radius of the front face
    }

    @Test
    void lowWallMustBeJumped() {
        WallShape low = types.build("LOW", new WallBuildContext(20, 0, 0, 18, 1.0, new Random(1)));
        assertTrue(hitAt(low, 10, 10, FLOOR, 0, STAND), "standing on the floor runs into a low wall");
        assertTrue(hitAt(low, 10, 10, FLOOR + 0.5, 0, STAND), "not high enough yet");
        assertFalse(hitAt(low, 10, 10, FLOOR + 0.8, 0, STAND), "feet above the bar clears it");
    }

    @Test
    void highWallMustBeSneakedUnder() {
        WallShape high = types.build("HIGH", new WallBuildContext(20, 0, 0, 18, 1.0, new Random(1)));
        assertTrue(hitAt(high, 10, 10, FLOOR, 0, STAND), "standing head hits a high wall");
        assertFalse(hitAt(high, 10, 10, FLOOR, 0, SNEAK), "sneaking (1.5 tall) fits underneath");
        assertTrue(hitAt(high, 10, 10, FLOOR + 0.8, 0, STAND), "jumping into it hits too");
    }

    @Test
    void aFastWallCannotTunnelThroughAPlayerBetweenTicks() {
        WallShape wall = centredGapWall();
        double playerX = 10, playerZ = 6;                        // solid part, stationary player
        // The wall jumps 4 blocks in one tick, from in front of the player to behind them:
        assertFalse(WallCollision.hits(wall, 0, 0, FLOOR, 12, 0, playerX, FLOOR, playerZ, STAND, R));
        assertFalse(WallCollision.hits(wall, 0, 0, FLOOR, 8, 0, playerX, FLOOR, playerZ, STAND, R));
        assertTrue(WallCollision.sweepHits(wall, 0, 0, FLOOR, 12, 0, 8, 0,
                playerX, FLOOR, playerZ, playerX, FLOOR, playerZ, STAND, R),
                "checking only the two end positions would miss this; the sweep must not");
    }

    @Test
    void aSweepThroughTheGapStillPasses() {
        WallShape wall = centredGapWall();
        assertFalse(WallCollision.sweepHits(wall, 0, 0, FLOOR, 12, 0, 8, 0,
                10, FLOOR, 0, 10, FLOOR, 0, STAND, R));
    }

    @Test
    void aPlayerRunningIntoTheWallIsCaughtEvenIfBothEndsMissIt() {
        WallShape wall = centredGapWall();
        // Wall stationary at s=10; player sprints from x=12.5 to x=7.5 in one tick on a solid part of the wall.
        assertTrue(WallCollision.sweepHits(wall, 0, 0, FLOOR, 10, 0, 10, 0,
                12.5, FLOOR, 6, 7.5, FLOOR, 6, STAND, R));
    }

    @Test
    void rotatedWallsUseTheirOwnFrame() {
        WallShape wall = centredGapWall();
        double quarterTurn = Math.PI / 2;                        // normal now +Z, lateral axis is -X
        assertFalse(WallCollision.hits(wall, 0, 0, FLOOR, 5, quarterTurn, 0, FLOOR, 5, STAND, R), "gap is on the centre line");
        assertTrue(WallCollision.hits(wall, 0, 0, FLOOR, 5, quarterTurn, 5, FLOOR, 5, STAND, R), "5 blocks to the side is solid");
    }

    @Test
    void wallsAreMeasuredFromTheArenaCentreNotTheWorldOrigin() {
        WallShape wall = centredGapWall();
        double cx = 1000, cz = -500;
        assertFalse(WallCollision.hits(wall, cx, cz, FLOOR, 10, 0, cx + 10, FLOOR, cz, STAND, R));
        assertTrue(WallCollision.hits(wall, cx, cz, FLOOR, 10, 0, cx + 10, FLOOR, cz + 6, STAND, R));
    }
}
