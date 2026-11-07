package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RadarRefreshScheduleTest {
    private static final NetworkId PHASE_ZERO = new NetworkId(new UUID(0, 0));
    private static final NetworkId PHASE_ONE = new NetworkId(new UUID(0, 1));

    @Test void knownTargetsUpdateEveryFourTicksAndImmediatelyAfterEachSearch() {
        List<Long> measurements = new ArrayList<>();
        for (long tick = 1; tick <= 20; tick++) {
            boolean searchStarted = tick == 1 || tick == 11;
            if (RadarRefreshSchedule.knownTargetsDue(tick, PHASE_ZERO, searchStarted)) measurements.add(tick);
        }
        assertEquals(List.of(1L, 4L, 8L, 11L, 12L, 16L, 20L), measurements);
    }

    @Test void differentNetworksSpreadMeasurementsAcrossServerTicks() {
        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();
        for (long tick = 0; tick < 12; tick++) {
            if (RadarRefreshSchedule.isDue(tick, PHASE_ZERO)) first.add(tick);
            if (RadarRefreshSchedule.isDue(tick, PHASE_ONE)) second.add(tick);
        }
        assertEquals(List.of(0L, 4L, 8L), first);
        assertEquals(List.of(1L, 5L, 9L), second);
    }
}
