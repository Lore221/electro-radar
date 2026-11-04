package com.electroradar.cannon.controller;

import java.util.Objects;
import java.util.function.Supplier;

import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.intercept.HostileShell;

/**
 * Срок полёта цели: последний индекс положения до падения ({@link HostileShell#predictImpact}).
 *
 * <p>Прогноз перебирает отрезки снаряда по миру, поэтому держится, пока снаряд идёт по прежнему
 * прогнозу: срок сдвигается на прошедшие тики. Отклонение от прогноза (рикошет, вода, другой
 * снаряд) или устаревание запускает перебор заново.</p>
 */
final class ShellImpactForecast {
    /** Сколько ходов цели проверять; дольше летящий снаряд получает этот срок без доказательства. */
    static final int HORIZON_TICKS = 400;
    private static final long REFRESH_TICKS = 20;
    private static final double DRIFT_TOLERANCE = 0.25D;

    private Object key;
    private long observedAt;
    private HostileShell observed;
    private int lastAirborne;

    /**
     * @param key идентичность цели
     * @param now серверный тик наблюдения
     * @return последний индекс положения в полёте относительно {@code now}, не меньше 0
     */
    int lastAirborne(Object key, long now, HostileShell shell, Supplier<TrajectoryTrace.Probe> probe) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(shell, "shell");
        if (key.equals(this.key) && observed != null && now >= observedAt && now - observedAt < REFRESH_TICKS) {
            int elapsed = (int) (now - observedAt);
            if (observed.positionAfter(elapsed).subtract(shell.position()).length() <= DRIFT_TOLERANCE) {
                return Math.max(0, lastAirborne - elapsed);
            }
        }
        this.key = key;
        observedAt = now;
        observed = shell;
        lastAirborne = shell.predictImpact(probe.get(), HORIZON_TICKS).lastAirborneIndex();
        return lastAirborne;
    }

    void reset() {
        key = null;
        observed = null;
    }
}
