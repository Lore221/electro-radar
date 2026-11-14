package com.electroradar.radar.alarm;

/** Счётчики текущего потока только для адресного профилирования, без зависимости от мира. */
final class ShellRouteMetrics {
    static final boolean ENABLED = Boolean.getBoolean("electroradar.profile.shell_alarm");
    private static final ThreadLocal<long[]> COUNTERS = ENABLED ? ThreadLocal.withInitial(() -> new long[3]) : null;
    private ShellRouteMetrics() { }

    static void searched(int evaluatedTicks) {
        long[] counters = COUNTERS.get();
        counters[0]++;
        counters[1] += evaluatedTicks;
    }

    static void fallback() { COUNTERS.get()[2]++; }
    static long searches() { return COUNTERS.get()[0]; }
    static long evaluatedTicks() { return COUNTERS.get()[1]; }
    static long fallbacks() { return COUNTERS.get()[2]; }
}
