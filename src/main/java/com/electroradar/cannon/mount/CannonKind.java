package com.electroradar.cannon.mount;

import java.util.Optional;
import com.electroradar.cannon.ballistics.ArcFamily;

/** Тип орудия определяет профиль, выбор семейства и способ запуска выстрела. */
public enum CannonKind {
    BIG_CANNON,
    AUTOCANNON,
    DROP_MORTAR;

    public boolean selectableTrajectory() {
        return this == BIG_CANNON;
    }

    public Optional<ArcFamily> family(ArcFamily selected) {
        return switch (this) {
            case BIG_CANNON -> Optional.of(selected);
            case AUTOCANNON -> Optional.empty();
            // Пока разрешена только верхняя дуга; это отдельная политика миномёта.
            case DROP_MORTAR -> Optional.of(ArcFamily.HIGH);
        };
    }

    public boolean controllerFires() {
        return this != DROP_MORTAR;
    }
}
