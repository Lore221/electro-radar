package com.electroradar.radar.simple;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadarRotationTest {
    @Test void pitchedParentProjectsRotorInsteadOfAddingAHeading() {
        double c = Math.sqrt(0.5);
        var frame = new com.electroradar.radar.spatial.SpatialTransform(
                net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO,
                new net.minecraft.world.phys.Vec3(1, 0, 0),
                new net.minecraft.world.phys.Vec3(0, c, c),
                new net.minecraft.world.phys.Vec3(0, -c, c));
        var rotation = new RadarRotation(100, 0).inFrame(frame);
        assertEquals(54.735610317245346, rotation.angleDegrees(109.375), 1e-9);
        assertEquals(90, rotation.angleDegrees(118.75), 1e-9);
        assertEquals(180, rotation.angleDegrees(137.5), 1e-9);
        assertEquals(270, rotation.angleDegrees(156.25), 1e-9);
        assertEquals(45, rotation.localAngleDegrees(109.375), 1e-9);
        assertEquals(45, rotation.localBearingDegrees(54.735610317245346).orElseThrow(), 1e-9);
    }

    @Test void yawAndRollProjectAllFourQuarterTurns() {
        double c = Math.sqrt(0.5);
        var frame = new com.electroradar.radar.spatial.SpatialTransform(
                net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO,
                new net.minecraft.world.phys.Vec3(0, c, -c),
                new net.minecraft.world.phys.Vec3(0, c, c),
                new net.minecraft.world.phys.Vec3(1, 0, 0));
        var rotation = new RadarRotation(0, 0).inFrame(frame);
        assertEquals(270, rotation.angleDegrees(0), 1e-9);
        assertEquals(0, rotation.angleDegrees(18.75), 1e-9);
        assertEquals(90, rotation.angleDegrees(37.5), 1e-9);
        assertEquals(180, rotation.angleDegrees(56.25), 1e-9);
        assertEquals(305.26438968275465, rotation.angleDegrees(9.375), 1e-9);
    }

    @Test void antennaAndBeamShareClockwiseQuarterTurnsAndFullRevolution() {
        var rotation = new RadarRotation(100, 0);
        assertEquals(0,rotation.angleDegrees(100),1e-9);
        assertEquals(90,rotation.angleDegrees(118.75),1e-9);
        assertEquals(180,rotation.angleDegrees(137.5),1e-9);
        assertEquals(0,rotation.angleDegrees(175),1e-9);
        var restored = new RadarRotation(500,rotation.angleDegrees(137.5));
        assertEquals(270,restored.angleDegrees(518.75),1e-9);
    }
}
