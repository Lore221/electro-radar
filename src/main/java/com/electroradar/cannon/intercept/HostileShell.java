package com.electroradar.cannon.intercept;

import java.util.Objects;

import com.electroradar.cannon.ballistics.TargetMotion;
import com.electroradar.cannon.ballistics.TrajectoryTrace;
import com.electroradar.cannon.ballistics.Vector3;

/**
 * Чужой снаряд большой пушки в мировых координатах, снятый в фазе BlockEntity тика наблюдения
 * {@code t₀}: он уже сделал ход этого тика, {@code position = P(0)}, {@code velocity = V(0)}.
 *
 * <p>Ход {@code m} (переход {@code P(m) → P(m+1)}) выполняется в фазе сущностей тика
 * {@code t₀ + 1 + m}; перед ним CBC проверяет столкновения на отрезке {@code P(m) → P(m) + V(m+1)}.
 * Снаряд выпущен раньше перехватчика, а {@code EntityTickList} обходит сущности в порядке
 * добавления, поэтому в каждом тике он ходит раньше перехватчика: отрезок перехватчика,
 * выпущенного в фазе BlockEntity тика {@code t₀ + L}, с номером {@code n} видит его в
 * {@code P(L + 1 + n)}.</p>
 *
 * @param position положение в тик наблюдения, мировые координаты
 * @param velocity скорость в тик наблюдения ({@code getDeltaMovement}), блоков за тик
 * @param motion закон движения его профиля (гравитация и сопротивление с множителями измерения)
 */
public record HostileShell(Vector3 position, Vector3 velocity, LinearDragMotion motion) {
    public HostileShell {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(motion, "motion");
    }

    /** Положение и скорость в произвольный тик, вычисленные по одному набору коэффициентов. */
    public HostileShell after(int ticks) {
        return motion.advance(this, ticks);
    }

    public Vector3 positionAfter(int ticks) {
        return position.add(motion.displacement(velocity, ticks));
    }

    public Vector3 velocityAfter(int ticks) {
        return motion.velocity(velocity, ticks);
    }

    /**
     * Цель для решателя: отрезку {@code n} перехватчика соответствует {@code P(delay + n)}
     * относительно {@code pivot}.
     *
     * @param delay ходы цели между наблюдением и отрезком 0 перехватчика: {@code L + 1} при прямом
     *              выстреле через {@code L} тиков после наблюдения
     */
    public TargetMotion asTarget(Vector3 pivot, int delay) {
        Objects.requireNonNull(pivot, "pivot");
        if (delay < 0) throw new IllegalArgumentException("Delay must be non-negative");
        return new TargetMotion() {
            @Override
            public Vector3 positionAt(int tick) {
                return positionAfter(delay + tick).subtract(pivot);
            }

            @Override
            public double speedBound(int tick) {
                return motion.stepBound(velocity, delay + tick);
            }
        };
    }

    /**
     * Прогноз падения: первый отрезок CBC {@code P(m) → P(m) + V(m+1)}, задевающий блок или
     * незагруженную область. Пробитие, рикошет и срабатывание собственного взрывателя не
     * моделируются: это граница, после которой снаряд в прогнозе больше не летит.
     *
     * @param probe проверка отрезка в мире; не должна подгружать чанки
     * @param maxTicks сколько ходов проверить
     */
    public Impact predictImpact(TrajectoryTrace.Probe probe, int maxTicks) {
        Objects.requireNonNull(probe, "probe");
        if (maxTicks < 0) throw new IllegalArgumentException("maxTicks must be non-negative");
        Vector3 end = position;
        for (int m = 0; m <= maxTicks; m++) {
            Vector3 start = positionAfter(m);
            end = start.add(velocityAfter(m + 1));
            TrajectoryTrace.Obstacle obstacle = probe.inspect(start, end);
            if (obstacle != null) return new Impact(obstacle.status(), m, obstacle.point());
        }
        return new Impact(TrajectoryTrace.Status.CLEAR, maxTicks, end);
    }

    /**
     * @param status {@code BLOCKED} — падение, {@code UNLOADED} — уходит в незагруженную область,
     *               {@code CLEAR} — летит дольше проверенного
     * @param tick ход {@code m}, на котором это происходит; до него снаряд в {@code P(0…m)}
     * @param point точка падения (или конец последнего проверенного отрезка)
     */
    public record Impact(TrajectoryTrace.Status status, int tick, Vector3 point) {
        public Impact {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(point, "point");
        }

        /** Последний индекс положения, в котором снаряд ещё гарантированно летит. */
        public int lastAirborneIndex() {
            return tick;
        }
    }
}
