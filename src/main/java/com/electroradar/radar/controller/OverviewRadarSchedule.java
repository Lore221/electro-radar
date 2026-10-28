package com.electroradar.radar.controller;

import java.util.ArrayList;
import java.util.Optional;

import com.electroradar.radar.network.ObservationWindowSchedule;
import com.electroradar.radar.visual.RadarSectorOrder;

/** Три луча обзорного модуля: одна зона на два базовых окна сети, по умолчанию 20 тиков. */
final class OverviewRadarSchedule {
    private static final int MAIN_SECTOR_COUNT = 3;
    private static final int ZONES_PER_MAIN_SECTOR = 6;
    private static final int NETWORK_WINDOWS_PER_VISIT = 2;

    private OverviewRadarSchedule() { }

    static ObservationWindowSchedule create(int storedVariant) {
        if (!RadarSectorOrder.validVariant(storedVariant)) {
            throw new IllegalArgumentException("Invalid overview radar order variant");
        }
        int[][] orders = new int[MAIN_SECTOR_COUNT][];
        for (int group = 0; group < MAIN_SECTOR_COUNT; group++) {
            int variant = 1 + Math.floorMod(storedVariant - 1 + group, RadarSectorOrder.VARIANT_COUNT);
            orders[group] = RadarSectorOrder.permutation(ZONES_PER_MAIN_SECTOR, variant);
        }
        return ObservationWindowSchedule.everyNetworkWindows(NETWORK_WINDOWS_PER_VISIT, (serverTick, networkWindow) -> {
            int step = (int) Math.floorMod(networkWindow.slotSerial(), (long) ZONES_PER_MAIN_SECTOR);
            ArrayList<Integer> active = new ArrayList<>(MAIN_SECTOR_COUNT);
            for (int group = 0; group < MAIN_SECTOR_COUNT; group++) {
                active.add(group * ZONES_PER_MAIN_SECTOR + orders[group][step]);
            }
            long fadeTicks = Math.multiplyExact(networkWindow.durationTicks(), (long) ZONES_PER_MAIN_SECTOR);
            return Optional.of(new ObservationWindowSchedule.ObservationWindow(active,
                    networkWindow.startTick(), networkWindow.endTick(), fadeTicks));
        });
    }
}
