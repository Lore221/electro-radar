package com.electroradar.radar.alarm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;
import com.electroradar.cannon.intercept.LinearDragMotion;

class ShellRoutePredictionTest {
    @Test void catchesFastCrossingWithBothEndpointsOutside() {
        ProtectionBox box = box(0, 0, 0, 1, 1, 1);
        HostileShell shell = shell(new Vector3(-50, .5, .5), new Vector3(100, 0, 0), 0, 0);
        var result = ShellRoutePrediction.predict(shell, box, 0, 10);
        assertEquals(0, result.firstDangerTick());
        assertEquals(0, result.lastDangerTick());
        assertTrue(result.complete());
        assertFalse(result.dangerousAfter(1));
    }

    @Test void usesCbcCollisionSweepRatherThanPositionChord() {
        // P(1).y = 9, но collision sweep первого хода идёт от y=10 до y=8.
        var result = ShellRoutePrediction.predict(shell(new Vector3(0, 10, 0), Vector3.ZERO, 0, 2),
                box(-1, 8.1, -1, 1, 8.3, 1), 0, 10);
        assertEquals(0, result.firstDangerTick());
    }

    @Test void alsoCatchesPhysicalMotionAcrossGapBetweenNativeSweeps() {
        // v0=10, k=.5: collision end=5, P1=7.5. Виртуальная зона x=6..7
        // пересечена перемещением, хотя оба штатных sweep обходят её.
        var result = ShellRoutePrediction.predict(shell(Vector3.ZERO, new Vector3(10, 0, 0), .5, 0),
                box(6, -1, -1, 7, 1, 1), 0, 10);
        assertEquals(0, result.firstDangerTick());
    }

    @Test void detectsFallingShellAfterItPassesHighAboveZone() {
        var falling = ShellRoutePrediction.predict(shell(new Vector3(-20, 40, .5), new Vector3(1, 0, 0), 0, .1),
                box(-50, 0, 0, 50, 10, 1), 0, 100);
        assertTrue(falling.firstDangerTick() > 0);
        assertTrue(falling.complete());
        var overflight = ShellRoutePrediction.predict(shell(new Vector3(-20, 40, .5), new Vector3(1, 0, 0), 0, 0),
                box(-50, 0, 0, 50, 10, 1), 0, 100);
        assertEquals(-1, overflight.firstDangerTick());
        assertTrue(overflight.complete());
    }

    @Test void doesNotRejectShellInitiallyBelowZoneButFlyingUp() {
        var result = ShellRoutePrediction.predict(shell(new Vector3(0, -10, 0), new Vector3(0, 5, 0), .01, .05),
                box(-1, 5, -1, 1, 10, 1), 0, 500);
        assertTrue(result.firstDangerTick() >= 0);
        assertTrue(result.complete());
    }

    @Test void includesProjectileReachAndBoundaryContact() {
        var shell = shell(new Vector3(-5, 1.2, .5), new Vector3(10, 0, 0), 0, 0);
        assertEquals(-1, ShellRoutePrediction.predict(shell, box(0, 0, 0, 1, 1, 1), 0, 5).firstDangerTick());
        assertEquals(0, ShellRoutePrediction.predict(shell, box(0, 0, 0, 1, 1, 1), .25, 5).firstDangerTick());
        assertTrue(box(0, 0, 0, 1, 1, 1).intersects(new Vector3(-1, 1, 0), new Vector3(2, 1, 0)));
        assertTrue(box(0, 0, 0, 1, 1, 1).intersects(new Vector3(.5, .5, .5), new Vector3(.5, .5, .5)));
    }

    @Test void finiteBudgetDoesNotClaimInfiniteSafety() {
        var result = ShellRoutePrediction.predict(shell(Vector3.ZERO, new Vector3(1, 0, 0), 0, 0),
                box(10_000, -1, -1, 10_001, 1, 1), 0, 100);
        assertEquals(-1, result.firstDangerTick());
        assertFalse(result.complete());
    }

    @Test void motionlessShellInsideZoneRemainsDangerous() {
        var result = ShellRoutePrediction.predict(shell(Vector3.ZERO, Vector3.ZERO, 0, 0),
                box(-1, -1, -1, 1, 1, 1), 0, 10);
        assertTrue(result.dangerousAfter(10_000));
        assertTrue(result.complete());
    }

    @Test void randomizedPredictionMatchesIndependentStepByStepCbcRecurrence() {
        Random random = new Random(20261105);
        for (int test = 0; test < 300; test++) {
            double k = test % 3 == 0 ? 0 : test % 3 == 1 ? 1e-10 : .01;
            double g = .01 + random.nextDouble() * .1;
            Vector3 position = new Vector3(random.nextDouble() * 100 - 50, random.nextDouble() * 100, random.nextDouble() * 100 - 50);
            Vector3 velocity = new Vector3(random.nextDouble() * 8 - 4, random.nextDouble() * 8 - 2, random.nextDouble() * 8 - 4);
            ProtectionBox zone = box(-15, 0, -15, 15, 40, 15);
            var predicted = ShellRoutePrediction.predict(shell(position, velocity, k, g), zone, .4, 500);
            int first = -1, last = -1;
            for (int n = 0; n < 500; n++) {
                Vector3 acceleration = velocity.scale(-k).add(new Vector3(0, -g, 0));
                Vector3 nextVelocity = velocity.add(acceleration);
                Vector3 end = position.add(nextVelocity);
                Vector3 moved = position.add(velocity).add(acceleration.scale(.5));
                // Независимый эталон: пересечения с шестью плоскостями граней, не slab test.
                if (referenceIntersects(position, end) || referenceIntersects(position, moved)) {
                    if (first < 0) first = n;
                    last = n;
                }
                position = moved;
                velocity = nextVelocity;
            }
            assertEquals(first, predicted.firstDangerTick(), "first case " + test);
            assertEquals(last, predicted.lastDangerTick(), "last case " + test);
        }
    }

    private static HostileShell shell(Vector3 p, Vector3 v, double k, double g) { return new HostileShell(p, v, new LinearDragMotion(k, g)); }
    private static boolean referenceIntersects(Vector3 p, Vector3 q) {
        double[] start = {p.x(), p.y(), p.z()}, end = {q.x(), q.y(), q.z()};
        double[] lo = {-15.4, -.4, -15.4}, hi = {15.4, 40.4, 15.4};
        boolean inside = true;
        for (int axis = 0; axis < 3; axis++) inside &= start[axis] >= lo[axis] && start[axis] <= hi[axis];
        if (inside) return true;
        for (int axis = 0; axis < 3; axis++) {
            double delta = end[axis] - start[axis];
            if (delta == 0) continue;
            for (double plane : new double[]{lo[axis], hi[axis]}) {
                double fraction = (plane - start[axis]) / delta;
                if (fraction < 0 || fraction > 1) continue;
                boolean onFace = true;
                for (int other = 0; other < 3; other++) {
                    if (other == axis) continue;
                    double value = start[other] + fraction * (end[other] - start[other]);
                    onFace &= value >= lo[other] && value <= hi[other];
                }
                if (onFace) return true;
            }
        }
        return false;
    }
    private static ProtectionBox box(double x, double y, double z, double a, double b, double c) {
        return new ProtectionBox(new Vector3(x, y, z), new Vector3(a, b, c));
    }
}
