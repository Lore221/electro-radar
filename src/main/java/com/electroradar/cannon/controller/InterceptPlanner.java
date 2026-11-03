package com.electroradar.cannon.controller;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.electroradar.cannon.ballistics.AimConstraints;
import com.electroradar.cannon.ballistics.FireSolution;
import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.FlightHorizon;
import com.electroradar.cannon.ballistics.InterceptRequest;
import com.electroradar.cannon.ballistics.InterceptResult;
import com.electroradar.cannon.ballistics.InterceptSearch;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.TargetForecast;
import com.electroradar.cannon.ballistics.Vector3;

/**
 * Связывает состояние орудия и цели с ядром решателя и хранит то, что не меняется от тика к тику:
 * коэффициенты профиля и горизонт.
 *
 * <p><b>Время.</b> Контроллер считает в фазе BlockEntity тика {@code T}, когда цель уже сдвинулась
 * в этом тике. Сигнал заставляет установку CBC запланировать тик блока; она стреляет в фазе
 * запланированных тиков {@code T + 1}, а снаряд проходит первый отрезок в фазе сущностей того же
 * тика, после сдвига цели. Поэтому отрезку {@code n} соответствует положение цели в
 * {@code T + 1 + n}.</p>
 */
final class InterceptPlanner {
    /** Тики от решения контроллера до выстрела CBC. */
    static final int LAUNCH_DELAY_TICKS = 1;
    /** Внешний предел горизонта для профилей без доказуемой границы (без гравитации и сопротивления). */
    static final int HORIZON_LIMIT_TICKS = 1_200;

    private ProjectileProfile profile;
    private FlightCoefficients flight;
    private HorizonKey horizonKey;
    private FlightHorizon horizon;

    /**
     * Ищет самое раннее попадание при выстреле в {@code T + 1}.
     *
     * @param pivot центр вращения установки, мировые координаты
     * @param clearance высота центра над нижней границей попадания, см. {@link FlightHorizon#clearance}
     * @param target оценка движения цели в тик {@code T}, мировые координаты
     * @param hint тик прежнего решения этой цели
     */
    Optional<FireSolution> plan(ProjectileProfile profile, Vector3 pivot, double clearance,
                                AimConstraints constraints, TargetTrack.Estimate target, OptionalInt hint) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(pivot, "pivot");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hint, "hint");
        InterceptRequest request = new InterceptRequest(flight(profile), forecast(pivot, target), constraints,
                horizon(constraints.maxPitch(), clearance), hint);
        InterceptResult result = InterceptSearch.search(request);
        return result.solution();
    }

    /** Прогноз цели относительно центра вращения в тиках полёта снаряда. */
    static TargetForecast forecast(Vector3 pivot, TargetTrack.Estimate target) {
        double delay = LAUNCH_DELAY_TICKS;
        return new TargetForecast(target.positionAfter(delay).subtract(pivot),
                target.velocity().add(target.acceleration().scale(delay)), target.acceleration());
    }

    private FlightCoefficients flight(ProjectileProfile next) {
        if (!next.equals(profile)) {
            profile = next;
            flight = FlightCoefficients.of(next);
            horizonKey = null;
        }
        return flight;
    }

    private FlightHorizon horizon(double maxPitch, double clearance) {
        HorizonKey key = new HorizonKey(maxPitch, Math.ceil(clearance));
        if (!key.equals(horizonKey)) {
            horizonKey = key;
            // Запас округлён вверх: горизонт не меняется от каждого покачивания платформы.
            horizon = FlightHorizon.compute(flight, maxPitch, key.clearance(), HORIZON_LIMIT_TICKS);
        }
        return horizon;
    }

    private record HorizonKey(double maxPitch, double clearance) {
    }
}
