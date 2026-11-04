package com.electroradar.radar.alarm;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ZoneBoundsTest {
    @Test void includesBothSelectedBlocksAndNormalizesCornerOrder() {
        ZoneBounds bounds = new ZoneBounds(10, 20, 30, 3, 4, 5);
        assertEquals(new ZoneBounds(3, 4, 5, 10, 20, 30), bounds);
        assertEquals(8, bounds.width());
        assertEquals(17, bounds.height());
        assertEquals(26, bounds.depth());
        assertEquals(11, bounds.geometry().max().x());
    }

    @Test void singleBlockZoneCannotShrinkToZeroOrFlipFaces() {
        ZoneBounds bounds = new ZoneBounds(0, 0, 0, 0, 0, 0);
        assertEquals(1, bounds.width());
        assertEquals(bounds, bounds.resize(0, 1, -1));
        assertEquals(bounds, bounds.resize(0, -1, -1));
        assertEquals(-1, bounds.resize(0, -1, 1).minX());
        assertEquals(1, bounds.resize(0, 1, 1).maxX());
    }

    @Test void checksSizeAndCoordinateLimitsWithoutIntegerOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new ZoneBounds(0, 0, 0, 2_048, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZoneBounds(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZoneBounds(0, -2_049, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZoneBounds(0, 0, 0, 0, 0, 0).resize(3, 1, 1));
        assertEquals(2_048, new ZoneBounds(0, 0, 0, 2_047, 0, 0).width());
    }
}
