package com.electroradar.cannon.mount;

import java.util.Objects;
import java.util.Optional;

import com.electroradar.cannon.ballistics.ProjectileProfile;

/**
 * Снаряд, который установка выпустит следующим, в терминах модели решателя.
 *
 * @param status можно ли рассчитывать выстрел
 * @param profile баллистика выстрела; есть только при {@link Status#READY}
 * @param reach половина наибольшего размера хитбокса снаряда: CBC расширяет на неё хитбокс цели
 */
public record CannonMunition(Status status, Optional<ProjectileProfile> profile, double reach) {
    public CannonMunition {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(profile, "profile");
        if ((status == Status.READY) != profile.isPresent() || !(reach >= 0.0D) || !Double.isFinite(reach)) {
            throw new IllegalArgumentException("Invalid cannon munition reading");
        }
    }

    public enum Status {
        READY,
        /** В стволе нет снаряда, пороха или патронов. */
        NO_AMMUNITION,
        /** Снаряд есть, но его полёт модель не описывает (например, квадратичное сопротивление). */
        UNSUPPORTED
    }

    public static CannonMunition ready(ProjectileProfile profile, double reach) {
        return new CannonMunition(Status.READY, Optional.of(profile), reach);
    }

    public static CannonMunition of(Status status) {
        return new CannonMunition(status, Optional.empty(), 0.0D);
    }
}
