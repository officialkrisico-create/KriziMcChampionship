package nl.kmc.blockball;

import nl.kmc.blockball.BallPhysics.Box;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BallPhysicsTest {

    // a 30 x 16 field, floor at y = 0, 6 high; goals of 8 wide and 3 deep at both ends
    private static final Box FIELD = new Box(0, 0, 0, 30, 6, 16);
    private static final Box GOAL_W = new Box(0, 0, 4, 3, 3, 12);
    private static final Box GOAL_E = new Box(27, 0, 4, 30, 3, 12);

    private static BallPhysics ball() {
        BallPhysics b = new BallPhysics(FIELD, 15, 0.45, 8, new BallPhysics.Config());
        for (Box x : BallPhysics.endZoneBlockers(FIELD, GOAL_W)) b.addBlocker(x);
        for (Box x : BallPhysics.endZoneBlockers(FIELD, GOAL_E)) b.addBlocker(x);
        return b;
    }

    private static void run(BallPhysics b, double seconds) {
        for (double t = 0; t < seconds; t += 0.1) b.step(0.1);
    }

    @Test
    void aDroppedBallBouncesAndComesToRest() {
        BallPhysics b = ball();
        b.y = 4;
        run(b, 6);
        assertEquals(b.groundY(), b.y, 0.01);
        assertEquals(0, b.vy, 0.01);
    }

    @Test
    void frictionStopsARollingBall() {
        BallPhysics b = ball();
        b.kick(1, 0, 6, 0);
        run(b, 10);
        assertEquals(0, b.vx, 0.001);
        assertEquals(0, b.vz, 0.001);
        assertTrue(b.x > 15 && b.x < 27, "it rolled forward and stayed on the field: " + b.x);
    }

    @Test
    void theSpeedLimitAlwaysHolds() {
        BallPhysics b = ball();
        b.kick(1, 0, 500, 500);
        assertTrue(b.speed() <= b.cfg.maxSpeed + 1e-9);
        for (int i = 0; i < 20; i++) b.kick(Math.random() - 0.5, Math.random() - 0.5, 90, 20);
        assertTrue(b.speed() <= b.cfg.maxSpeed + 1e-9);
    }

    @Test
    void repeatedKicksDoNotAccumulateSpeed() {
        BallPhysics b = ball();
        for (int i = 0; i < 10; i++) b.kick(0, 1, 12, 0);
        assertTrue(Math.hypot(b.vx, b.vz) < 12 * 1.2, "speed stays near one kick: " + Math.hypot(b.vx, b.vz));
    }

    @Test
    void wallsKeepTheBallInsideAndBounceIt() {
        BallPhysics b = ball();
        b.z = 8;
        b.kick(0, -1, 25, 0);
        for (int i = 0; i < 200; i++) {
            b.step(0.1);
            assertTrue(b.valid(), "step " + i);
            assertTrue(b.z >= FIELD.minZ() + b.cfg.radius - 1e-9);
        }
        b.reset();
        b.kick(0, -1, 25, 0);
        b.step(0.1);
        b.step(0.1);
        b.step(0.1);
        assertTrue(b.vz != 0 || b.z > FIELD.minZ());
    }

    @Test
    void aFastBallDoesNotTunnelThroughTheEndWall() {
        BallPhysics b = ball();
        b.x = 5; b.z = 2;                 // in the closed part of the end zone, aimed straight at the back wall
        b.vx = -80;                       // way above the limit: clamped, but a big step must still not escape
        for (int i = 0; i < 50; i++) {
            b.step(0.5);
            assertTrue(b.valid());
        }
    }

    @Test
    void theBlockersCloseTheEndZoneAroundTheGoal() {
        BallPhysics b = ball();
        b.x = 5; b.y = 0.45; b.z = 2;     // in the corner next to the goal
        b.kick(-1, 0, 15, 0);
        run(b, 3);
        // the blocker keeps it from sliding into the (only 8 wide) goal mouth region of the corner
        assertTrue(b.x > GOAL_W.maxX() - 0.01 || b.z < GOAL_W.minZ() + 0.01 || b.z > GOAL_W.maxZ() - 0.01);
        assertEquals(-1, b.goalAt(GOAL_W, GOAL_E));
    }

    @Test
    void aBallRolledIntoTheGoalIsDetected() {
        BallPhysics b = ball();
        b.x = 10; b.z = 8;
        b.kick(-1, 0, 14, 0);
        int goal = -1;
        for (int i = 0; i < 100 && goal < 0; i++) {
            b.step(0.1);
            goal = b.goalAt(GOAL_W, GOAL_E);
        }
        assertEquals(0, goal);
    }

    @Test
    void anInvalidStateIsRepaired() {
        BallPhysics b = ball();
        run(b, 1);
        double gx = b.x;
        b.x = Double.NaN;
        b.step(0.1);
        assertTrue(b.valid());
        assertEquals(gx, b.x, 1.0);
        b.x = 9999;
        b.step(0.1);
        assertTrue(b.valid());
    }

    @Test
    void aFrozenBallDoesNotMove() {
        BallPhysics b = ball();
        b.setFrozen(true);
        b.kick(1, 0, 20, 5);
        double x = b.x;
        run(b, 1);
        assertEquals(x, b.x);
        assertEquals(0, b.speed());
    }

    @Test
    void endZoneBlockersExistForGoalsOnEveryEnd() {
        Box north = new Box(10, 0, 0, 20, 3, 3);
        assertEquals(2, BallPhysics.endZoneBlockers(FIELD, north).size());
        Box south = new Box(10, 0, 13, 20, 3, 16);
        assertEquals(2, BallPhysics.endZoneBlockers(FIELD, south).size());
        List<Box> west = BallPhysics.endZoneBlockers(FIELD, GOAL_W);
        assertEquals(2, west.size());
        assertTrue(west.stream().allMatch(b -> b.maxX() <= GOAL_W.maxX() + 1e-9));
    }
}
