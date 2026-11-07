package com.electroradar.radar.alarm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.ballistics.Vector3;
import com.electroradar.cannon.intercept.HostileShell;
import com.electroradar.cannon.intercept.LinearDragMotion;

/** Зона на движущейся структуре: повёрнута её позой и движется вместе с ней. */
class MovingZoneTest {
    /** Поворот на 90° вокруг вертикали: локальная +X смотрит в мировую +Z. */
    private static final ZoneFrame QUARTER_TURN = new ZoneFrame(Vector3.ZERO, new Vector3(100, 0, 100),
            new Vector3(0, 0, 1), new Vector3(0, 1, 0), new Vector3(-1, 0, 0));

    @Test void rotatedZoneIsTestedInItsOwnAxes() {
        // Локально узкая по Z и длинная по X: после поворота длинная сторона идёт вдоль мировой Z.
        ZoneVolume zone = new ZoneVolume(box(0, 0, 0, 10, 2, 1), QUARTER_TURN, Vector3.ZERO);
        assertTrue(zone.contains(new Vector3(99.5, 1, 108)));
        assertFalse(zone.contains(new Vector3(108, 1, 100.5)), "unrotated image would contain this");
        ProtectionBox bounds = zone.worldBounds();
        assertEquals(99, bounds.min().x(), 1e-9);
        assertEquals(110, bounds.max().z(), 1e-9);
    }

    @Test void frameRoundTripsPoints() {
        Random random = new Random(7);
        ZoneFrame frame = new ZoneFrame(new Vector3(3, 4, 5), new Vector3(-20, 64, 9),
                unit(1, 0, 1), new Vector3(0, 1, 0), unit(-1, 0, 1));
        for (int i = 0; i < 100; i++) {
            Vector3 point = new Vector3(random.nextDouble() * 50, random.nextDouble() * 50, random.nextDouble() * 50);
            assertEquals(0, frame.toLocal(frame.toWorld(point)).subtract(point).length(), 1e-9);
        }
    }

    @Test void fixedZoneGivesTheSamePredictionAsTheBox() {
        ProtectionBox box = box(-2, -10, 10, 2, 10, 20);
        HostileShell shell = shell(Vector3.ZERO, new Vector3(0, 0, 2), .01, .025);
        assertEquals(ShellRoutePrediction.predict(shell, box, .25, 4096),
                ShellRoutePrediction.predict(shell, ZoneVolume.fixed(box), .25, 4096));
    }

    @Test void zoneMovingTowardTheShellIsReachedEarlier() {
        HostileShell shell = shell(Vector3.ZERO, new Vector3(1, 0, 0), 0, 0);
        ProtectionBox box = box(40, -1, -1, 41, 1, 1);
        int fixed = ShellRoutePrediction.predict(shell, box, 0, 200).firstDangerTick();
        int approaching = ShellRoutePrediction.predict(shell,
                new ZoneVolume(box, ZoneFrame.IDENTITY, new Vector3(-1, 0, 0)), 0, 200).firstDangerTick();
        assertEquals(39, fixed);
        assertTrue(approaching >= 18 && approaching <= 20, "met at ~20 ticks, was " + approaching);
    }

    @Test void zoneOutrunningTheShellIsNeverReached() {
        HostileShell shell = shell(Vector3.ZERO, new Vector3(1, 0, 0), .01, 0);
        var result = ShellRoutePrediction.predict(shell,
                new ZoneVolume(box(10, -1, -1, 11, 1, 1), ZoneFrame.IDENTITY, new Vector3(2, 0, 0)), 0, 4096);
        assertEquals(-1, result.firstDangerTick());
        assertTrue(result.complete(), "relative speed only decreases: shell stays behind");
    }

    @Test void slowingShellIsCaughtByAZoneFollowingIt() {
        // Снаряд впереди зоны и быстрее её, но сопротивление гасит его скорость: зона догоняет.
        HostileShell shell = shell(new Vector3(5, 0, 0), new Vector3(2, 0, 0), .1, 0);
        var result = ShellRoutePrediction.predict(shell,
                new ZoneVolume(box(0, -1, -1, 1, 1, 1), ZoneFrame.IDENTITY, new Vector3(.5, 0, 0)), 0, 4096);
        assertTrue(result.firstDangerTick() > 0, "an early-exit on the current relative speed would miss this");
    }

    @Test void trackerReusesPredictionWhileTheZoneFollowsItsVelocity() {
        ShellThreatTracker tracker = new ShellThreatTracker();
        UUID id = UUID.randomUUID();
        HostileShell shell = shell(Vector3.ZERO, new Vector3(0, 0, 2), .01, .025);
        Vector3 velocity = new Vector3(.3, 0, 0);
        for (int age = 0; age < 5; age++) {
            ZoneFrame frame = new ZoneFrame(Vector3.ZERO, velocity.scale(age), new Vector3(1, 0, 0),
                    new Vector3(0, 1, 0), new Vector3(0, 0, 1));
            tracker.refresh(List.of(observe(id, age, shell)), new ZoneVolume(box(-2, -10, 10, 2, 10, 20), frame, velocity),
                    java.util.Set.of(), null);
        }
        assertEquals(1, tracker.predictionCount());
        ZoneFrame jumped = new ZoneFrame(Vector3.ZERO, new Vector3(30, 0, 0), new Vector3(1, 0, 0),
                new Vector3(0, 1, 0), new Vector3(0, 0, 1));
        tracker.refresh(List.of(observe(id, 5, shell)), new ZoneVolume(box(-2, -10, 10, 2, 10, 20), jumped, velocity),
                java.util.Set.of(), null);
        assertEquals(2, tracker.predictionCount());
    }

    private static Vector3 unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new Vector3(x / length, y / length, z / length);
    }

    private static ProtectionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new ProtectionBox(new Vector3(x0, y0, z0), new Vector3(x1, y1, z1));
    }

    private static HostileShell shell(Vector3 position, Vector3 velocity, double drag, double gravity) {
        return new HostileShell(position, velocity, new LinearDragMotion(drag, gravity));
    }

    private static ShellThreatTracker.Observation observe(UUID id, int age, HostileShell initial) {
        return new ShellThreatTracker.Observation(id, age,
                new HostileShell(initial.positionAfter(age), initial.velocityAfter(age), initial.motion()), .25);
    }
}
