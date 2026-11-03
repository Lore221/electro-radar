package com.electroradar.cannon.ballistics;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Одна задача наведения при фиксированном моменте запуска.
 *
 * <p>Продолжать поиск с {@link InterceptResult#nextTick()} можно только для той же задачи: при
 * новом прогнозе пропущенные тики больше не доказанно пусты, и перебор начинается заново.</p>
 *
 * @param flight коэффициенты профиля снаряда
 * @param target прогноз цели относительно центра пушки
 * @param constraints угол крепления и семейство траектории
 * @param horizon последний проверяемый тик
 * @param hintTick тик прежнего решения: он и соседние проверяются первыми свежим прогнозом, и
 *                 найденное там решение предпочитается более раннему (см. {@link InterceptSearch})
 */
public record InterceptRequest(FlightCoefficients flight, TargetForecast target, AimConstraints constraints,
                               FlightHorizon horizon, OptionalInt hintTick) {
    public InterceptRequest {
        Objects.requireNonNull(flight, "flight");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(hintTick, "hintTick");
        if (hintTick.isPresent() && hintTick.getAsInt() < 0) {
            throw new IllegalArgumentException("Hint tick must be non-negative");
        }
    }

    public static InterceptRequest of(FlightCoefficients flight, TargetForecast target, AimConstraints constraints,
                                      FlightHorizon horizon) {
        return new InterceptRequest(flight, target, constraints, horizon, OptionalInt.empty());
    }

    public InterceptRequest withHint(int tick) {
        return new InterceptRequest(flight, target, constraints, horizon, OptionalInt.of(tick));
    }
}
