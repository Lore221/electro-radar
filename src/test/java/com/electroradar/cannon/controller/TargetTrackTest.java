package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.electroradar.cannon.ballistics.Vector3;

class TargetTrackTest {
    @Test
    void measuresVelocityFromDisplacementNotReportedMotion() {
        TargetTrack track = new TargetTrack();
        Vector3 velocity = new Vector3(0.3D, 0.0D, -0.2D);
        // Игрок на сервере сообщает нулевую скорость: оценка должна опираться на смещение.
        track.sample("player", 100L, Vector3.ZERO, Vector3.ZERO, false);
        TargetTrack.Estimate estimate = track.sample("player", 101L, velocity, Vector3.ZERO, false);
        assertEquals(0.0D, estimate.velocity().subtract(velocity).length(), 1.0E-12D);
        assertEquals(1, estimate.lockTicks());
    }

    @Test
    void usesAccelerationOnlyAfterWarmupAndConvergesToIt() {
        TargetTrack track = new TargetTrack();
        Vector3 acceleration = new Vector3(0.01D, -0.04D, 0.0D);
        Vector3 velocity = new Vector3(0.5D, 0.6D, 0.1D);
        TargetTrack.Estimate estimate = null;
        for (int tick = 0; tick < 40; tick++) {
            Vector3 position = velocity.scale(tick).add(acceleration.scale(0.5D * tick * tick));
            estimate = track.sample("bird", tick, position, velocity, false);
            if (tick < TargetTrack.ACCELERATION_WARMUP_TICKS) {
                assertEquals(Vector3.ZERO, estimate.acceleration(), "ускорение до прогрева");
            }
        }
        assertEquals(0.0D, estimate.acceleration().subtract(acceleration).length(), 1.0E-9D);
        // Смещение за последний тик отстаёт от мгновенной скорости на a/2 — так и описывает движение Minecraft.
        Vector3 lastStep = velocity.add(acceleration.scale(39.0D - 0.5D));
        assertEquals(0.0D, estimate.velocity().subtract(lastStep).length(), 1.0E-9D);
    }

    @Test
    void groundedTargetHasNoVerticalMotion() {
        TargetTrack track = new TargetTrack();
        track.sample("zombie", 0L, Vector3.ZERO, new Vector3(0.1D, -0.0784D, 0.0D), true);
        TargetTrack.Estimate estimate = track.sample("zombie", 1L, new Vector3(0.1D, -0.0001D, 0.0D),
                Vector3.ZERO, true);
        assertEquals(0.0D, estimate.velocity().y(), 0.0D);
        assertEquals(0.0D, estimate.acceleration().y(), 0.0D);
    }

    @Test
    void newTargetOrGapRestartsHistory() {
        TargetTrack track = new TargetTrack();
        track.sample("a", 0L, Vector3.ZERO, Vector3.ZERO, false);
        track.sample("a", 1L, new Vector3(1.0D, 0.0D, 0.0D), Vector3.ZERO, false);
        TargetTrack.Estimate other = track.sample("b", 2L, new Vector3(50.0D, 0.0D, 0.0D),
                new Vector3(0.0D, 0.0D, 0.2D), false);
        assertEquals(0, other.lockTicks());
        assertEquals(new Vector3(0.0D, 0.0D, 0.2D), other.velocity());
        TargetTrack.Estimate afterGap = track.sample("b", 30L, new Vector3(60.0D, 0.0D, 0.0D), Vector3.ZERO, false);
        assertEquals(0, afterGap.lockTicks());
    }
}
