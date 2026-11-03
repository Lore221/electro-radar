package com.electroradar.cannon.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import com.electroradar.cannon.ballistics.Vector3;

class ShotAssociationTest {
    @Test void matchesOnlyOnceInTheRightDimensionAndTimeWindow() {
        var tracker = new ShotAssociation<String>();
        tracker.remember("controller", "overworld", 100, Vector3.ZERO, "shot");
        assertTrue(tracker.match("nether", 101, Vector3.ZERO).isEmpty());
        assertEquals("shot", tracker.match("overworld", 101, new Vector3(0.0001, 0, 0)).orElseThrow());
        assertTrue(tracker.match("overworld", 101, Vector3.ZERO).isEmpty());
        tracker.remember("controller", "overworld", 100, Vector3.ZERO, "expired");
        assertTrue(tracker.match("overworld", 103, Vector3.ZERO).isEmpty());
    }

    @Test void ambiguousLaunchesAndOtherPositionsAreNotGuessed() {
        var tracker = new ShotAssociation<String>();
        tracker.remember("a", "world", 10, Vector3.ZERO, "a");
        tracker.remember("b", "world", 10, Vector3.ZERO, "b");
        assertTrue(tracker.match("world", 11, Vector3.ZERO).isEmpty());
        assertTrue(tracker.match("world", 11, new Vector3(1, 0, 0)).isEmpty());
        tracker.clear();
        assertTrue(tracker.match("world", 11, Vector3.ZERO).isEmpty());
    }

    @Test void latestControllerPlanReplacesPreviousSnapshot() {
        var tracker = new ShotAssociation<String>();
        tracker.remember("a", "world", 10, Vector3.ZERO, "old");
        tracker.remember("a", "world", 11, new Vector3(1, 0, 0), "new");
        assertTrue(tracker.match("world", 12, Vector3.ZERO).isEmpty());
        assertEquals("new", tracker.match("world", 12, new Vector3(1, 0, 0)).orElseThrow());
    }
}
