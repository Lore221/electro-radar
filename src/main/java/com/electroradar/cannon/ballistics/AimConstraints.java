package com.electroradar.cannon.ballistics;

import java.util.Objects;
import java.util.Optional;

/**
 * Ограничения, которые проверяются для каждого корня: допустимый угол места крепления и требуемое
 * семейство траектории. Доступность направления для привода сюда не входит — её решает
 * потребитель поиска (см. {@link InterceptSearch}).
 *
 * @param minPitch наименьший угол места в градусах, положительный вверх
 * @param maxPitch наибольший угол места в градусах
 * @param family требуемое семейство; пусто — подходит любое
 */
public record AimConstraints(double minPitch, double maxPitch, Optional<ArcFamily> family) {
    /** Допуск сравнения угла с границей крепления, градусы. */
    private static final double PITCH_TOLERANCE = 1.0E-9D;

    public AimConstraints {
        Objects.requireNonNull(family, "family");
        if (!(minPitch >= -90.0D) || !(maxPitch <= 90.0D) || !(minPitch <= maxPitch)) {
            throw new IllegalArgumentException("Invalid pitch range");
        }
    }

    public static AimConstraints pitchRange(double minPitch, double maxPitch) {
        return new AimConstraints(minPitch, maxPitch, Optional.empty());
    }

    public AimConstraints withFamily(ArcFamily required) {
        return new AimConstraints(minPitch, maxPitch, Optional.of(required));
    }

    boolean allowsPitch(double pitch) {
        return pitch >= minPitch - PITCH_TOLERANCE && pitch <= maxPitch + PITCH_TOLERANCE;
    }

    boolean allowsBranch(ArcBranch branch) {
        return family.isEmpty() || branch.belongsTo(family.get());
    }
}
