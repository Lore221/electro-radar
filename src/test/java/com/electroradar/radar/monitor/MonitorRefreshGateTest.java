package com.electroradar.radar.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MonitorRefreshGateTest {
    @Test void firstRefreshIsImmediateAndThenWaitsForTheInterval() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        assertTrue(gate.due(100, false));
        gate.refreshed(100);
        for (long tick = 101; tick < 100 + MonitorRefreshGate.INTERVAL_TICKS; tick++) {
            assertFalse(gate.due(tick, false), "tick " + tick);
        }
        assertTrue(gate.due(100 + MonitorRefreshGate.INTERVAL_TICKS, false));
    }

    @Test void urgentChangesBypassTheInterval() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        gate.refreshed(100);
        assertTrue(gate.due(101, true));
    }

    @Test void resetAndClockGoingBackwardsRefreshImmediately() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        gate.refreshed(100);
        assertTrue(gate.due(50, false));
        gate.reset();
        assertTrue(gate.due(101, false));
    }
}
