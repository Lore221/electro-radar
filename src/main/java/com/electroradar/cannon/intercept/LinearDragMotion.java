package com.electroradar.cannon.intercept;

import com.electroradar.cannon.ballistics.FlightCoefficients;
import com.electroradar.cannon.ballistics.ProjectileProfile;
import com.electroradar.cannon.ballistics.Sweep;
import com.electroradar.cannon.ballistics.Vector3;

/**
 * Рекурсия CBC {@code p ← p + v + a/2}, {@code v ← v + a}, {@code a = −k·v − g·ŷ} для произвольной
 * начальной скорости {@code V₀} в замкнутом виде:
 * {@code P(m) = P₀ + A(m)·V₀ − g·B(m)·ŷ}, {@code V(m) = q^m·V₀ − g·S(m)·ŷ}.
 *
 * <p>Коэффициенты {@code A, B, S, q^m} берутся у {@link FlightCoefficients} профиля с единичной
 * скоростью, нулевым смещением и единичной гравитацией: у его отрезка {@code radius = A(m)},
 * {@code drop = B(m)}, {@code step = q^(m+1)}, {@code fall = S(m+1)}. Так прогноз чужого снаряда
 * считается той же устойчивой арифметикой, что и полёт собственного.</p>
 */
public final class LinearDragMotion {
    private final double drag;
    private final double gravity;
    private final FlightCoefficients unit;

    /**
     * @param drag линейное сопротивление {@code k}, {@code 0 ≤ k < 1}, уже с множителем измерения
     * @param gravity модуль ускорения вниз {@code g}, уже с множителем измерения
     */
    public LinearDragMotion(double drag, double gravity) {
        if (!(drag >= 0.0D && drag < 1.0D) || !(gravity >= 0.0D) || !Double.isFinite(gravity)) {
            throw new IllegalArgumentException("Invalid linear drag motion");
        }
        this.drag = drag;
        this.gravity = gravity;
        this.unit = FlightCoefficients.of(ProjectileProfile.of(1.0D, 0.0D, drag, 1.0D));
    }

    public double drag() {
        return drag;
    }

    public double gravity() {
        return gravity;
    }

    /** Смещение за {@code ticks} обновлений: {@code A·V₀ − g·B·ŷ}. */
    public Vector3 displacement(Vector3 initialVelocity, int ticks) {
        Sweep sweep = unit.sweep(ticks);
        double along = sweep.radius();
        return new Vector3(initialVelocity.x() * along,
                initialVelocity.y() * along - gravity * sweep.drop(),
                initialVelocity.z() * along);
    }

    /** Скорость после {@code ticks} обновлений: {@code q^m·V₀ − g·S(m)·ŷ}. */
    public Vector3 velocity(Vector3 initialVelocity, int ticks) {
        Sweep sweep = unit.sweep(ticks);
        double decay = decay(sweep);
        return new Vector3(initialVelocity.x() * decay,
                initialVelocity.y() * decay - gravity * sum(sweep),
                initialVelocity.z() * decay);
    }

    /**
     * Граница смещения за один тик на {@code [0, ticks]}: {@code |P(m+1) − P(m)| = |(1 − k/2)·V(m) − g/2·ŷ|
     * ≤ |V₀| + g·S(ticks) + g/2}, потому что {@code |V(m)| ≤ |V₀| + g·S(m)}, а {@code S} не убывает.
     */
    public double stepBound(Vector3 initialVelocity, int ticks) {
        return initialVelocity.length() + gravity * (sum(unit.sweep(ticks)) + 0.5D);
    }

    /** {@code q^m = step/(1 − k)}. */
    private double decay(Sweep sweep) {
        return sweep.step() / (1.0D - drag);
    }

    /** {@code S(m) = S(m + 1) − q^m}; не отрицательна. */
    private double sum(Sweep sweep) {
        return Math.max(0.0D, sweep.fall() - decay(sweep));
    }
}
