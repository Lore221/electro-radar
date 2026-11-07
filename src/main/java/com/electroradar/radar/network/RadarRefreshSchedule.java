package com.electroradar.radar.network;

/** Общая фаза измерений известных целей и снимков мониторов одной сети. */
public final class RadarRefreshSchedule {
    public static final long INTERVAL_TICKS = 4;

    private RadarRefreshSchedule() { }

    /** Использует часы менеджера сети, общие для всех игровых миров. */
    public static boolean isDue(long serverTick, NetworkId networkId) {
        long phase = networkId == null ? 0 : Math.floorMod(networkId.value().hashCode(), INTERVAL_TICKS);
        return Math.floorMod(serverTick, INTERVAL_TICKS) == phase;
    }

    /** Полный поиск сразу измеряет найденный набор, даже между обычными шагами. */
    public static boolean knownTargetsDue(long serverTick, NetworkId networkId, boolean newVisit) {
        return newVisit || isDue(serverTick, networkId);
    }
}
