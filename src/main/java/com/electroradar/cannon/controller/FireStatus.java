package com.electroradar.cannon.controller;

import java.util.Locale;

/**
 * Главная причина, по которой контроллер сейчас стреляет или не стреляет. Порядок проверок задаёт
 * {@link TargetControllerBlockEntity}; здесь только устойчивые ключи перевода для очков Create.
 */
public enum FireStatus {
    READY,
    AIMING,
    TARGET_LOCKING,
    NO_NETWORK,
    NO_TARGET,
    TARGET_LOST,
    TARGET_UNSUPPORTED,
    NO_CANNON,
    WRONG_SIDE,
    CANNON_BUSY,
    NO_AMMUNITION,
    UNSUPPORTED_MUNITION,
    TARGET_TOO_CLOSE,
    TARGET_UNREACHABLE,
    TRAJECTORY_BLOCKED,
    TRAJECTORY_UNLOADED;

    public String translationKey() {
        return "goggles.electroradar.target_controller.status." + name().toLowerCase(Locale.ROOT);
    }
}
