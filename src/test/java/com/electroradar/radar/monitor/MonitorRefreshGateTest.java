package com.electroradar.radar.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.RadarRefreshSchedule;

import org.junit.jupiter.api.Test;

class MonitorRefreshGateTest {
    @Test void firstRefreshIsImmediateAndThenWaitsForTheInterval() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        assertTrue(gate.due(100, false, null));
        gate.refreshed(100);
        for (long tick = 101; tick < 100 + MonitorRefreshGate.INTERVAL_TICKS; tick++) {
            assertFalse(gate.due(tick, false, null), "tick " + tick);
        }
        assertTrue(gate.due(100 + MonitorRefreshGate.INTERVAL_TICKS, false, null));
    }

    @Test void urgentChangesBypassTheInterval() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        gate.refreshed(100);
        assertTrue(gate.due(101, true, null));
    }

    @Test void resetAndClockGoingBackwardsRefreshImmediately() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        gate.refreshed(100);
        assertTrue(gate.due(50, false, null));
        gate.reset();
        assertTrue(gate.due(101, false, null));
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
                gate.refreshAndPublish(tick, true, null, () -> snapshot[0]++, () -> addressed.add(snapshot[0]));
            }
            gate.refreshAndPublish(tick, false, null, () -> snapshot[0]++, () -> {
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
            gate.refreshAndPublish(now, false, null, () -> refreshedAt.add(now), () -> publishedAt.add(now));
        }
        assertEquals(List.of(0L, 4L, 8L), refreshedAt);
        assertEquals(12, publishedAt.size(), "watcher joins and pending delivery must not wait for a rebuild");
    }

    @Test void addressedRefreshDoesNotMoveTheNetworksNextMeasurementPhase() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        NetworkId network = new NetworkId(new UUID(0, 1));
        List<Long> refreshedAt = new ArrayList<>();
        gate.refreshAndPublish(100, true, network, () -> refreshedAt.add(100L), () -> { });
        for (long tick = 101; tick <= 105; tick++) {
            long now = tick;
            gate.refreshAndPublish(now, false, network, () -> refreshedAt.add(now), () -> { });
        }
        assertEquals(List.of(100L, 101L, 105L), refreshedAt,
                "the addressed request must not postpone radar measurements published at 101 and 105");
    }

    @Test void monitorLoadedBetweenMeasurementsJoinsTheExistingNetworkPhase() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        NetworkId network = new NetworkId(new UUID(0, 1));
        List<Long> refreshedAt = new ArrayList<>();
        for (long tick = 102; tick <= 109; tick++) {
            long now = tick;
            gate.refreshAndPublish(now, false, network, () -> refreshedAt.add(now), () -> { });
        }
        assertEquals(List.of(102L, 105L, 109L), refreshedAt);
    }

    @Test void monitorReadsEachNewRadarMeasurementInTheSameServerTickDespiteAddressedRequests() {
        MonitorRefreshGate gate = new MonitorRefreshGate();
        NetworkId network = new NetworkId(new UUID(0, 1));
        long[] sampledAt = {97};
        List<String> refreshes = new ArrayList<>();
        for (long tick = 100; tick <= 109; tick++) {
            long now = tick;
            // Manager Pre публикует измерение раньше BlockEntity тика монитора.
            if (RadarRefreshSchedule.knownTargetsDue(now, network, false)) sampledAt[0] = now;
            if (now == 100 || now == 104 || now == 108) {
                gate.refreshAndPublish(now, true, network,
                        () -> refreshes.add(now + ":" + sampledAt[0]), () -> { });
            }
            gate.refreshAndPublish(now, false, network,
                    () -> refreshes.add(now + ":" + sampledAt[0]), () -> { });
        }
        assertEquals(List.of("100:97", "101:101", "104:101", "105:105", "108:105", "109:109"), refreshes);
    }
}
