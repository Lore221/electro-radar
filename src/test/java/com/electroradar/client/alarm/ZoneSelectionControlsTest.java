package com.electroradar.client.alarm;

import com.electroradar.radar.alarm.ZoneBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ZoneSelectionControlsTest {
    @Test void airRangeRemainsWithinCreatesOneToOneHundredBlockLimits() {
        assertEquals(11, ZoneSelectionControls.range(10, 1));
        assertEquals(9, ZoneSelectionControls.range(10, -1));
        assertEquals(1, ZoneSelectionControls.range(10, -1000));
        assertEquals(100, ZoneSelectionControls.range(10, 1000));
        assertEquals(10, ZoneSelectionControls.range(10, Double.NaN));
    }

    @ParameterizedTest
    @CsvSource({
        "0,-1,1,0,0,4,4,4", "0,1,0,0,0,3,4,4",
        "1,-1,0,1,0,4,4,4", "1,1,0,0,0,4,3,4",
        "2,-1,0,0,1,4,4,4", "2,1,0,0,0,4,4,3"
    })
    void positiveWheelOutsideContractsEveryFace(int axis, int direction,
            int x0, int y0, int z0, int x1, int y1, int z1) {
        ZoneBounds zone = new ZoneBounds(0, 0, 0, 4, 4, 4);
        assertEquals(new ZoneBounds(x0, y0, z0, x1, y1, z1),
                zone.resize(axis, direction, ZoneSelectionControls.resizeStep(.25, false)));
    }

    @Test void wheelFromInsideReversesDirectionAndRoundsFractionalMotionAwayFromZero() {
        assertEquals(1, ZoneSelectionControls.resizeStep(.25, true));
        assertEquals(-1, ZoneSelectionControls.resizeStep(-.25, true));
        assertEquals(-1, ZoneSelectionControls.resizeStep(.25, false));
        assertEquals(1, ZoneSelectionControls.resizeStep(-.25, false));
        assertEquals(-3, ZoneSelectionControls.resizeStep(2.2, false));
        assertEquals(0, ZoneSelectionControls.resizeStep(Double.NaN, false));
    }

    @Test void largeWheelMotionClampsAtTheOppositeFaceWithoutFlippingTheZone() {
        ZoneBounds zone = new ZoneBounds(0, 0, 0, 4, 4, 4);
        int step = ZoneSelectionControls.resizeStep(1000, false);
        assertEquals(new ZoneBounds(0, 0, 0, 0, 4, 4), zone.resize(0, 1, step));
        assertEquals(new ZoneBounds(4, 0, 0, 4, 4, 4), zone.resize(0, -1, step));
    }
}
