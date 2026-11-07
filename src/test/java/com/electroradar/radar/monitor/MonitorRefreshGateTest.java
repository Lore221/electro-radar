package com.electroradar.radar.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

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

    @Test void repeatedAddressedRefreshesCannotStarveTrackingBroadcasts() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        int[] snapshot = {0};
        int[] broadcast = {0};
        List<Integer> addressed = new ArrayList<>();
        List<Integer> delivered = new ArrayList<>();
        for (long tick = 100; tick <= 120; tick++) {
            // Другой игрок открывает GUI чаще обычного интервала; адресная отправка
            // не подтверждает рассылку уже наблюдающему игроку.
            if (tick % 3 == 2) {
                gate.refreshAndPublish(tick, true, () -> snapshot[0]++, () -> addressed.add(snapshot[0]));
            }
            gate.refreshAndPublish(tick, false, () -> snapshot[0]++, () -> {
                if (snapshot[0] != broadcast[0]) {
                    broadcast[0] = snapshot[0];
                    delivered.add(broadcast[0]);
                }
            });
            assertEquals(snapshot[0], broadcast[0], "pending snapshot must reach the regular watcher at tick " + tick);
        }
        assertTrue(addressed.size() > 1);
        assertTrue(delivered.containsAll(addressed));
    }

    @Test void ordinaryRefreshesRemainLimitedWhileDeliveryCanRunBetweenThem() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        List<Long> refreshedAt = new ArrayList<>();
        List<Long> publishedAt = new ArrayList<>();
        for (long tick = 0; tick < 12; tick++) {
            long now = tick;
            gate.refreshAndPublish(now, false, () -> refreshedAt.add(now), () -> publishedAt.add(now));
        }
        assertEquals(List.of(0L, 4L, 8L), refreshedAt);
        assertEquals(12, publishedAt.size(), "watcher joins and pending delivery must not wait for a rebuild");
    }
}
