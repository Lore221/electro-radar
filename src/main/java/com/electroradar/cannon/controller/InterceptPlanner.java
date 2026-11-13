package com.electroradar.cannon.controller;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

import net.minecraft.world.phys.Vec3;

import com.electroradar.radar.spatial.SpatialTransform;

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
 * <p><b>Время.</b> Контроллер работает в фазе BlockEntity тика T после обновления сущностей.
 * Отрезку n соответствует положение цели T+d+n, где d — задержка до первого sweep снаряда,
 * см. {@link com.electroradar.cannon.mount.CannonKind#launchDelayTicks()}: 1 при прямом fireShot,
 * 2 у миномёта, который CBC запускает сам в фазе сущностей.</p>
 */
final class InterceptPlanner {
    /** Внешний предел горизонта для профилей без доказуемой границы (без гравитации и сопротивления). */
    static final int HORIZON_LIMIT_TICKS = 1_200;

    private ProjectileProfile profile;
    private FlightCoefficients flight;
    private HorizonKey horizonKey;
    private FlightHorizon horizon;

    /**
     * Ищет попадание с первым обновлением снаряда в {@code T + launchDelay}; подсказка может сохранить
     * прежнее решение.
     *
     * @param pivot центр вращения установки, мировые координаты
     * @param clearance высота центра над нижней границей попадания, см. {@link FlightHorizon#clearance}
     * @param target оценка движения цели в тик {@code T}, мировые координаты
     * @param launchDelay тики от решения до первого sweep снаряда
     * @param hint тик прежнего решения этой цели
     */
    Optional<FireSolution> plan(ProjectileProfile profile, Vector3 pivot, double clearance,
                                AimConstraints constraints, TargetTrack.Estimate target, int launchDelay,
                                OptionalInt hint) {
        return plan(profile, pivot, clearance, constraints, target, launchDelay, hint, direction -> true);
    }

    private Optional<FireSolution> plan(ProjectileProfile profile, Vector3 pivot, double clearance,
                                        AimConstraints constraints, TargetTrack.Estimate target, int launchDelay,
                                        OptionalInt hint, Predicate<Vector3> aimable) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(pivot, "pivot");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hint, "hint");
        InterceptRequest request = new InterceptRequest(flight(profile), forecast(pivot, target, launchDelay),
                constraints, horizon(constraints.maxPitch(), clearance), hint);
        InterceptResult result = InterceptSearch.search(request, 0, Integer.MAX_VALUE,
                solution -> aimable.test(solution.direction()));
        return result.solution();
    }

    /**
     * Центр из пространства установки переводится в мир, где задана цель и действует гравитация.
     * Пределы крепления проверяются после обратного преобразования направления решения.
     */
    Optional<FireSolution> planMounted(ProjectileProfile profile, Vector3 storagePivot, SpatialTransform frame,
                                       int minBuildHeight, double reach, AimConstraints localConstraints,
                                       TargetTrack.Estimate target, int launchDelay, OptionalInt hint) {
        Vec3 world = frame.toWorld(new Vec3(storagePivot.x(), storagePivot.y(), storagePivot.z()));
        Vector3 pivot = new Vector3(world.x, world.y, world.z);
        AimConstraints worldConstraints = frame.isIdentity() ? localConstraints
                : new AimConstraints(-90, 90, localConstraints.family());
        return plan(profile, pivot, FlightHorizon.clearance(pivot.y(), minBuildHeight, reach),
                worldConstraints, target, launchDelay, hint,
                direction -> MountFrame.aimable(frame, localConstraints.minPitch(), localConstraints.maxPitch(), direction));
    }

    /** Прогноз цели относительно центра вращения в тиках полёта снаряда. */
    static TargetForecast forecast(Vector3 pivot, TargetTrack.Estimate target, int launchDelay) {
        if (launchDelay < 0) throw new IllegalArgumentException("launchDelay must be non-negative");
        double delay = launchDelay;
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
