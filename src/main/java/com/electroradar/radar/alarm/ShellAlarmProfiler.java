package com.electroradar.radar.alarm;

import java.util.Locale;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Короткая сводка за 20 обновлений одного Alarm; выключенный режим не читает часы. */
final class ShellAlarmProfiler {
    static final boolean ENABLED = ShellRouteMetrics.ENABLED;
    private static final Logger LOGGER = LogUtils.getLogger();
    enum Stage { SETUP, ZONE, QUERY, OBSERVE, FORECAST, PUBLISH }
    private final long[] stages = new long[Stage.values().length];
    private long start, mark, total, peak, searchesAtStart, evaluatedAtStart, fallbacksAtStart;
    private long searches, evaluated, fallbacks, predictions, targets, shells;
    private int ticks, peakTargets, peakShells;

    void begin() {
        searchesAtStart = ShellRouteMetrics.searches();
        evaluatedAtStart = ShellRouteMetrics.evaluatedTicks();
        fallbacksAtStart = ShellRouteMetrics.fallbacks();
        start = mark = System.nanoTime();
    }

    void end(Stage stage) {
        long now = System.nanoTime();
        stages[stage.ordinal()] += now - mark;
        mark = now;
    }

    void targets(int count) { targets += count; peakTargets = Math.max(peakTargets, count); }
    void shells(int count) { shells += count; peakShells = Math.max(peakShells, count); }

    void finish(ServerLevel level, BlockPos position, int newPredictions) {
        long elapsed = mark - start;
        total += elapsed;
        peak = Math.max(peak, elapsed);
        searches += ShellRouteMetrics.searches() - searchesAtStart;
        evaluated += ShellRouteMetrics.evaluatedTicks() - evaluatedAtStart;
        fallbacks += ShellRouteMetrics.fallbacks() - fallbacksAtStart;
        predictions += newPredictions;
        if (++ticks < 20) return;
        LOGGER.info("[ElectroRadar/ShellAlarmPerf] dimension={} alarm={} category=SHELL ticks={} avgUs={} maxUs={} setupUs={} queryAndZoneUs={} zoneUs={} queryUs={} observeUs={} forecastUs={} publishUs={} targetsAvg={} targetsMax={} shellsAvg={} shellsMax={} predictions={} searches={} evaluatedTicks={} fallbacks={}",
                level.dimension().location(), position, ticks, us(total), decimal(peak / 1000.0),
                us(Stage.SETUP), us(stages[Stage.ZONE.ordinal()] + stages[Stage.QUERY.ordinal()]),
                us(Stage.ZONE), us(Stage.QUERY), us(Stage.OBSERVE), us(Stage.FORECAST), us(Stage.PUBLISH),
                decimal(targets / (double) ticks), peakTargets, decimal(shells / (double) ticks), peakShells,
                predictions, searches, evaluated, fallbacks);
        java.util.Arrays.fill(stages, 0);
        ticks = peakTargets = peakShells = 0;
        total = peak = searches = evaluated = fallbacks = predictions = targets = shells = 0;
    }

    private String us(long nanos) { return decimal(nanos / (1000.0 * ticks)); }
    private String us(Stage stage) { return us(stages[stage.ordinal()]); }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
