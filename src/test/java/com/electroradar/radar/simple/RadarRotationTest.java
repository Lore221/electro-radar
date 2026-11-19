package com.electroradar.radar.simple;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadarRotationTest {
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
