package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AimDriveTest {
    private static final double MAX_SPEED = AimDrive.degreesPerTick(64.0D);
    private static final double ACCELERATION = AimDrive.degreesPerTickSquared(32.0D);

    @Test
    void reachesStaticTargetWithoutOvershootWithinLimits() {
        AimDrive drive = new AimDrive();
        double yaw = 0.0D;
        double pitch = 0.0D;
        double previousYawStep = 0.0D;
        boolean settled = false;
        for (long tick = 0; tick < 400; tick++) {
            AimDrive.Step step = drive.step("target", tick, yaw, pitch, 170.0D, 35.0D, MAX_SPEED, ACCELERATION);
            double yawStep = step.yaw() - yaw;
            assertTrue(Math.abs(yawStep) <= MAX_SPEED + 1.0E-9D, "speed limit");
            assertTrue(Math.abs(yawStep - previousYawStep) <= ACCELERATION + 1.0E-9D || Math.abs(170.0D - step.yaw()) < 1.0E-9D,
                    "acceleration limit at " + tick);
            assertTrue(step.yaw() <= 170.0D + 1.0E-9D && step.pitch() <= 35.0D + 1.0E-9D, "no overshoot");
            previousYawStep = yawStep;
            yaw = step.yaw();
            pitch = step.pitch();
            settled = step.settled();
        }
        assertEquals(170.0D, yaw, 1.0E-6D);
        assertEquals(35.0D, pitch, 1.0E-6D);
        assertTrue(settled);
    }

    @Test
    void turnsTheShortWayAcrossNorth() {
        AimDrive drive = new AimDrive();
        AimDrive.Step step = drive.step("target", 0L, 170.0D, 0.0D, -170.0D, 0.0D, MAX_SPEED, ACCELERATION);
        assertTrue(step.yaw() > 170.0D, "должен идти через 180°, а не через 0°: " + step.yaw());
    }

    @Test
    void feedForwardKeepsUpWithMovingTarget() {
        AimDrive drive = new AimDrive();
        double yaw = 0.0D;
        double rate = 0.8D;
        AimDrive.Step step = null;
        for (long tick = 0; tick < 300; tick++) {
            step = drive.step("target", tick, yaw, 10.0D, rate * tick, 10.0D, MAX_SPEED, ACCELERATION);
            yaw = step.yaw();
        }
        assertEquals(rate * 299, yaw, 0.05D);
        assertTrue(step.settled(), "устойчивое сопровождение должно считаться готовностью");
    }

    @Test
    void wrapsDegrees() {
        assertEquals(-170.0D, AimDrive.wrapDegrees(190.0D), 1.0E-12D);
        assertEquals(170.0D, AimDrive.wrapDegrees(-190.0D), 1.0E-12D);
        assertEquals(-180.0D, AimDrive.wrapDegrees(180.0D), 1.0E-12D);
    }
}
