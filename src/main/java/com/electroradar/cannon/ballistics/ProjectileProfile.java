package com.electroradar.cannon.ballistics;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Баллистика одного выстрела CBC при постоянном линейном сопротивлении.
 *
 * <p>Сопротивление CBC равно {@code min(drag·density·|v|, |v|)} вдоль скорости
 * ({@code AbstractCannonProjectile.getDragForce}), поэтому вне жидкостей это линейное
 * сопротивление с {@code k = drag·density}. Гравитация и {@code drag} снаряда уже должны быть
 * умножены на множители измерения. Квадратичное сопротивление ({@code quadratic_drag}) этой
 * моделью не описывается — адаптер обязан отказать в решении, а не подставлять профиль.</p>
 *
 * @param speed начальная скорость {@code s}, блоков за тик
 * @param spawnOffset осевое смещение точки появления снаряда от центра пушки {@code L}
 * @param drag линейное сопротивление {@code k} за тик, {@code 0 ≤ k < 1}
 * @param gravity модуль ускорения вниз {@code g} за тик (в данных CBC хранится со знаком минус)
 * @param lifetimeTicks время жизни снаряда, если оно есть (автопушки); иначе пусто
 */
public record ProjectileProfile(double speed, double spawnOffset, double drag, double gravity,
                                OptionalInt lifetimeTicks) {
    public ProjectileProfile {
        Objects.requireNonNull(lifetimeTicks, "lifetimeTicks");
        if (!(speed > 0.0D) || !Double.isFinite(speed)
                || !(spawnOffset >= 0.0D) || !Double.isFinite(spawnOffset)
                || !(drag >= 0.0D && drag < 1.0D)
                || !(gravity >= 0.0D) || !Double.isFinite(gravity)
                || lifetimeTicks.isPresent() && lifetimeTicks.getAsInt() < 0) {
            throw new IllegalArgumentException("Invalid projectile profile");
        }
    }

    /** Профиль без ограничения времени жизни — например, снаряд большой пушки. */
    public static ProjectileProfile of(double speed, double spawnOffset, double drag, double gravity) {
        return new ProjectileProfile(speed, spawnOffset, drag, gravity, OptionalInt.empty());
    }

    public ProjectileProfile withLifetime(int ticks) {
        return new ProjectileProfile(speed, spawnOffset, drag, gravity, OptionalInt.of(ticks));
    }
}
